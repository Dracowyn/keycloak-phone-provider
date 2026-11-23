package cc.coopersoft.keycloak.phone.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2.2.0 安全修复的回归测试，在真实 Keycloak 容器中验证：
 * <ul>
 *   <li>伪造 / 他人的 {@code SMS_OTP_ANSWERED} Cookie 不能跳过短信第二因子，合法签发的 Cookie 仍可免验证；</li>
 *   <li>Everybody 直授流程中同一验证码不能重放；</li>
 *   <li>连续输错 5 次只作废当前验证码，不清空重发冷却；</li>
 *   <li>注册表单 validate 阶段只校验不消费，其他字段出错时验证码仍可用，注册成功后号码被标记为已验证；</li>
 *   <li>首次配置短信 MFA：无号码用户可绑定新号码，已有已验证号码的用户不能借此换号；</li>
 *   <li>号码绑定 / 解绑接口只认 Bearer，跨站带身份 Cookie 的请求被拒；签发客户端不在白名单内的令牌被拒；</li>
 *   <li>并发错误猜测绕不过输错上限，并发发送绕不过重发冷却；</li>
 *   <li>请求号码先校验再规范化：逗号列表、重复区号前缀等被拒，区号变体共用同一个冷却；
 *   验证码被成功消费后可以立即重发。</li>
 * </ul>
 */
class SecurityFixesIT {

    private static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.8.2";
    private static final String REALM = "sec";
    private static final String CLIENT_ID = "it-app";
    /** 号码写接口默认白名单里唯一的客户端：Keycloak 自带的账户中心。 */
    private static final String ACCOUNT_CONSOLE = "account-console";
    private static final String REDIRECT_URI = "http://localhost/cb";
    private static final String COOKIE_NAME = "SMS_OTP_ANSWERED";
    private static final Pattern CODE_IN_LOG = Pattern.compile(">>>\\s*([0-9]{4,8})");
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVIL_ORIGIN = "https://evil.example";
    /** 并发用例的请求数：与审查实测时的并发度一致。 */
    private static final int CONCURRENT_GUESSES = 40;
    private static final int CONCURRENT_SENDS = 20;
    /** 单条验证码允许的最多比对次数，与 TokenCodeServiceImpl.MAX_VERIFICATION_ATTEMPTS 一致。 */
    private static final int MAX_VERIFICATION_ATTEMPTS = 5;
    /** 发送成功后占位保留的秒数，与 PhoneMessageServiceImpl 一致；测试按它加余量等待。 */
    private static final int SEND_RESERVATION_AFTER_SUCCESS_SECONDS = 5;

    private static KeycloakContainer keycloak;

    @BeforeAll
    static void startKeycloak() throws Exception {
        keycloak = new KeycloakContainer(System.getProperty("keycloak.image", DEFAULT_IMAGE))
                .withProviderLibsFrom(locateProviderJars())
                .withCopyFileToContainer(MountableFile.forClasspathResource("areacode.json"), "/areacode.json")
                .withCopyFileToContainer(MountableFile.forClasspathResource("theme/phone-it"),
                        "/opt/keycloak/themes/phone-it")
                .withStartupTimeout(Duration.ofMinutes(5));
        keycloak.start();
        importRealm();
    }

    @AfterAll
    static void stopKeycloak() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    // ---------- 短信 MFA 免验证 Cookie ----------

    @Test
    @DisplayName("伪造的 SMS_OTP_ANSWERED Cookie 不能跳过短信 OTP")
    void forgedCookieDoesNotSkipSmsOtp() throws Exception {
        Browser browser = new Browser();
        browser.addCookie(COOKIE_NAME, "anything");

        HttpResponse<String> afterPassword = browser.loginWithPassword("alice", "alice-pass");

        assertThat(afterPassword.statusCode())
                .as("伪造 Cookie 时应停在短信验证码页面，实际: %s", describe(afterPassword))
                .isEqualTo(200);
        assertThat(afterPassword.body()).contains("kc-sms-otp-form");
    }

    @Test
    @DisplayName("合法签发的 Cookie 可免验证，但不能给其他用户使用")
    void issuedCookieIsBoundToUser() throws Exception {
        // bob 完成一次完整的密码 + 短信登录，拿到签名 Cookie
        Browser bobBrowser = new Browser();
        int logMark = keycloak.getLogs().length();
        HttpResponse<String> otpPage = bobBrowser.loginWithPassword("bob", "bob-pass");
        assertThat(otpPage.body()).as("应进入短信验证码页面: %s", describe(otpPage)).contains("kc-sms-otp-form");
        String code = awaitCodeFromLog(logMark);
        assertThat(code).isNotNull();

        HttpResponse<String> done = bobBrowser.submitForm(otpPage.body(), Map.of("smsCode", code));
        assertThat(done.statusCode()).as("正确验证码应完成登录: %s", describe(done)).isEqualTo(302);
        assertThat(done.headers().firstValue("Location").orElse("")).startsWith(REDIRECT_URI).contains("code=");

        String issued = extractSetCookie(done, COOKIE_NAME);
        assertThat(issued).as("应签发 JWS 形式的 Cookie").isNotNull().matches("[^.]+\\.[^.]+\\.[^.]+");

        // 新浏览器只带这枚 Cookie：bob 再次登录应跳过短信
        Browser bobAgain = new Browser();
        bobAgain.addCookie(COOKIE_NAME, issued);
        HttpResponse<String> skipped = bobAgain.loginWithPassword("bob", "bob-pass");
        assertThat(skipped.statusCode()).as("本人 Cookie 应跳过短信: %s", describe(skipped)).isEqualTo(302);
        assertThat(skipped.headers().firstValue("Location").orElse("")).startsWith(REDIRECT_URI);

        // carol 拿 bob 的 Cookie 不能跳过
        Browser carol = new Browser();
        carol.addCookie(COOKIE_NAME, issued);
        HttpResponse<String> carolPage = carol.loginWithPassword("carol", "carol-pass");
        assertThat(carolPage.statusCode()).as("他人 Cookie 不应生效: %s", describe(carolPage)).isEqualTo(200);
        assertThat(carolPage.body()).contains("kc-sms-otp-form");
    }

    // ---------- 验证码一次性消费 ----------

    @Test
    @DisplayName("Everybody 直授流程中同一验证码不能重放")
    void everybodyFlowCodeCannotBeReplayed() throws Exception {
        String phone = "13900000004";
        int logMark = keycloak.getLogs().length();
        assertThat(sendAuthenticationCode(phone).statusCode()).isEqualTo(200);
        String code = awaitCodeFromLog(logMark);
        assertThat(code).isNotNull();

        HttpResponse<String> first = phoneGrant(phone, code);
        assertThat(first.statusCode()).as("首次提交应签发令牌: %s", first.body()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).has("access_token")).isTrue();

        HttpResponse<String> replay = phoneGrant(phone, code);
        assertThat(replay.statusCode()).as("重放应被拒绝: %s", replay.body()).isEqualTo(401);
    }

    // ---------- 频控 ----------

    @Test
    @DisplayName("连续输错 5 次作废验证码但保留重发冷却")
    void maxFailedAttemptsKeepsResendCooldown() throws Exception {
        String phone = "13900000005";
        int logMark = keycloak.getLogs().length();
        assertThat(sendAuthenticationCode(phone).statusCode()).isEqualTo(200);
        String code = awaitCodeFromLog(logMark);
        assertThat(code).isNotNull();
        assertThat(sendAuthenticationCode(phone).statusCode()).as("冷却期内重发").isEqualTo(429);

        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(phoneGrant(phone, wrong).statusCode()).isEqualTo(401);
        }

        assertThat(phoneGrant(phone, code).statusCode()).as("输错 5 次后正确验证码也应作废").isEqualTo(401);
        HttpResponse<String> resend = sendAuthenticationCode(phone);
        assertThat(resend.statusCode()).as("冷却不应被清空: %s", resend.body()).isEqualTo(429);
    }

    @Test
    @DisplayName("输错 5 次作废全部未过期验证码，更早发出的验证码不会重新生效")
    void maxFailedAttemptsExpiresOlderCodesToo() throws Exception {
        String phone = "13900000010";
        int logMark = keycloak.getLogs().length();
        assertThat(sendAuthenticationCode(phone).statusCode()).isEqualTo(200);
        String older = awaitCodeFromLog(logMark);
        assertThat(older).isNotNull();

        // 等 dummy 的 60 秒重发冷却结束，再发第二条；第一条仍在 300 秒有效期内
        Thread.sleep(Duration.ofSeconds(61).toMillis());
        logMark = keycloak.getLogs().length();
        HttpResponse<String> second = sendAuthenticationCode(phone);
        assertThat(second.statusCode()).as("冷却结束后重发: %s", second.body()).isEqualTo(200);
        String latest = awaitCodeFromLog(logMark);
        assertThat(latest).isNotNull();

        String wrong = Stream.of("000000", "111111", "222222")
                .filter(c -> !c.equals(older) && !c.equals(latest)).findFirst().orElseThrow();
        for (int i = 0; i < 5; i++) {
            assertThat(phoneGrant(phone, wrong).statusCode()).isEqualTo(401);
        }

        assertThat(phoneGrant(phone, older).statusCode()).as("更早的验证码不应重新生效").isEqualTo(401);
        assertThat(phoneGrant(phone, latest).statusCode()).as("最新验证码已作废").isEqualTo(401);
    }

    // ---------- 注册表单 ----------

    @Test
    @DisplayName("注册表单：其他字段出错不消费验证码，成功后号码已验证且用户数据完整")
    void registrationConsumesCodeOnlyOnSuccess() throws Exception {
        String phone = "13900000006";
        int logMark = keycloak.getLogs().length();
        HttpResponse<String> sent = sendCode("registration-code", phone);
        assertThat(sent.statusCode()).as("发送注册验证码: %s", sent.body()).isEqualTo(200);
        String code = awaitCodeFromLog(logMark);
        assertThat(code).isNotNull();

        Browser browser = new Browser();
        String registrationUrl = "/realms/" + REALM + "/protocol/openid-connect/registrations?client_id=" + CLIENT_ID
                + "&response_type=code&scope=openid&redirect_uri="
                + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8);
        HttpResponse<String> page = browser.get(registrationUrl);
        assertThat(page.statusCode()).as("注册页: %s", describe(page)).isEqualTo(200);

        Map<String, String> fields = new LinkedHashMap<>(Map.of(
                "username", "frank", "email", "frank@example.com", "firstName", "frank", "lastName", "it",
                "password", "frank-pass-1", "areaCode", "86", "phoneNumber", phone, "smsCode", code));
        fields.put("password-confirm", "mismatch");
        HttpResponse<String> invalid = browser.submitForm(page.body(), fields);
        assertThat(invalid.statusCode()).as("密码确认不一致应停留在注册页: %s", describe(invalid)).isEqualTo(200);

        fields.put("password-confirm", "frank-pass-1");
        HttpResponse<String> done = browser.submitForm(invalid.body(), fields);
        assertThat(done.statusCode()).as("验证码未被前一次提交消费，应注册成功: %s", describe(done)).isEqualTo(302);
        assertThat(done.headers().firstValue("Location").orElse("")).startsWith(REDIRECT_URI);

        String token = adminToken();
        JsonNode users = JSON.readTree(adminGet("/admin/realms/" + REALM + "/users?exact=true&username=frank", token).body());
        assertThat(users).hasSize(1);
        JsonNode frank = users.get(0);
        assertThat(frank.path("attributes").path("phoneNumber").get(0).asText()).isEqualTo("+86 " + phone);
        assertThat(frank.path("attributes").path("phoneNumberVerified").get(0).asText()).isEqualTo("true");
        assertThat(frank.get("email").asText()).isEqualTo("frank@example.com");

        JsonNode credentials = JSON.readTree(
                adminGet("/admin/realms/" + REALM + "/users/" + frank.get("id").asText() + "/credentials", token).body());
        assertThat(credentials.findValuesAsText("type")).contains("password");
    }

    // ---------- 首次配置短信 MFA ----------

    @Test
    @DisplayName("首次配置短信 MFA：无号码用户验证码通过后绑定号码并创建凭据")
    void configureSmsOtpBindsPhoneForUserWithoutNumber() throws Exception {
        String phone = "13900000007";
        Browser browser = new Browser();
        HttpResponse<String> configPage = browser.followLoginActions(browser.loginWithPassword("grace", "grace-pass"));
        assertThat(configPage.body()).as("应进入短信 MFA 配置页: %s", describe(configPage))
                .contains("kc-sms-otp-config-form");

        int logMark = keycloak.getLogs().length();
        assertThat(sendCode("verification-code", phone).statusCode()).isEqualTo(200);
        String code = awaitCodeFromLog(logMark);
        assertThat(code).isNotNull();

        String wrong = code.equals("000000") ? "111111" : "000000";
        HttpResponse<String> rejected = browser.submitForm(configPage.body(),
                Map.of("areaCode", "86", "phoneNumber", phone, "smsCode", wrong));
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("kc-sms-otp-config-form").contains("kc-error");

        HttpResponse<String> done = browser.submitForm(rejected.body(),
                Map.of("areaCode", "86", "phoneNumber", phone, "smsCode", code));
        assertThat(done.statusCode()).as("正确验证码应完成配置: %s", describe(done)).isEqualTo(302);
        assertThat(done.headers().firstValue("Location").orElse("")).startsWith(REDIRECT_URI);

        JsonNode grace = findUser("grace");
        assertThat(grace.path("attributes").path("phoneNumber").get(0).asText()).isEqualTo("+86 " + phone);
        assertThat(grace.path("attributes").path("phoneNumberVerified").get(0).asText()).isEqualTo("true");
        assertThat(credentialTypes(grace)).contains("phone-otp");
    }

    @Test
    @DisplayName("首次配置短信 MFA：已有已验证号码的用户不能换成其他号码，用原号码可完成配置")
    void configureSmsOtpKeepsExistingVerifiedNumber() throws Exception {
        String own = "13900000008";
        String other = "13900000009";
        Browser browser = new Browser();
        HttpResponse<String> configPage = browser.followLoginActions(browser.loginWithPassword("henry", "henry-pass"));
        assertThat(configPage.body()).as("应进入短信 MFA 配置页: %s", describe(configPage))
                .contains("kc-sms-otp-config-form");

        int logMark = keycloak.getLogs().length();
        assertThat(sendCode("verification-code", other).statusCode()).isEqualTo(200);
        String otherCode = awaitCodeFromLog(logMark);
        assertThat(otherCode).isNotNull();

        HttpResponse<String> rejected = browser.submitForm(configPage.body(),
                Map.of("areaCode", "86", "phoneNumber", other, "smsCode", otherCode));
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("smsOtpPhoneNumberMismatch");

        JsonNode unchanged = findUser("henry");
        assertThat(unchanged.path("attributes").path("phoneNumber").get(0).asText()).isEqualTo("+86 " + own);
        assertThat(credentialTypes(unchanged)).doesNotContain("phone-otp");

        logMark = keycloak.getLogs().length();
        assertThat(sendCode("verification-code", own).statusCode()).isEqualTo(200);
        String ownCode = awaitCodeFromLog(logMark);
        HttpResponse<String> done = browser.submitForm(rejected.body(),
                Map.of("areaCode", "86", "phoneNumber", own, "smsCode", ownCode));
        assertThat(done.statusCode()).as("原号码应完成配置: %s", describe(done)).isEqualTo(302);
        assertThat(credentialTypes(findUser("henry"))).contains("phone-otp");
    }

    // ---------- 号码绑定接口只认 Bearer ----------

    @Test
    @DisplayName("update-profile 与 unset：只带身份 Cookie 的跨站写请求被拒（401），号码不变")
    void phoneWritesRejectIdentityCookie() throws Exception {
        Browser browser = loginWithSms("kate", "kate-pass");
        assertThat(browser.cookies).containsKey("KEYCLOAK_IDENTITY");
        String attacker = "13900000023";
        String code = sendCodeAndAwait("verification-code", attacker);

        HttpResponse<String> update = browser.send(crossSitePost("/update-profile",
                form(Map.of("areaCode", "86", "phoneNumber", attacker, "smsCode", code))));
        assertThat(update.statusCode()).as("Cookie 不应被认作登录身份: %s", update.body()).isEqualTo(401);
        assertThat(JSON.readTree(update.body()).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

        HttpResponse<String> unset = browser.send(crossSitePost("/update-profile/unset", ""));
        assertThat(unset.statusCode()).as("解绑也不认 Cookie: %s", unset.body()).isEqualTo(401);

        JsonNode kate = findUser("kate");
        assertThat(kate.path("attributes").path("phoneNumber").get(0).asText()).isEqualTo("+86 13900000011");
        assertThat(kate.path("attributes").path("phoneNumberVerified").get(0).asText()).isEqualTo("true");
    }

    @Test
    @DisplayName("update-profile：白名单内客户端（account-console）签发的 Bearer 请求成功，号码换绑并标记为已验证")
    void phoneWriteAcceptsBearerToken() throws Exception {
        String own = "13900000021";
        // Everybody 直授为新号码自动开户，用户名即号码；令牌由默认白名单内的 account-console 签发
        HttpResponse<String> grant = phoneGrant(ACCOUNT_CONSOLE, own, sendCodeAndAwait("authentication-code", own));
        assertThat(grant.statusCode()).as("取用户令牌: %s", grant.body()).isEqualTo(200);
        String accessToken = JSON.readTree(grant.body()).get("access_token").asText();
        String target = "13900000022";
        String code = sendCodeAndAwait("verification-code", target);

        HttpResponse<String> update = http().send(crossSitePost("/update-profile",
                        form(Map.of("areaCode", "86", "phoneNumber", target, "smsCode", code)))
                        .header("Authorization", "Bearer " + accessToken).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(update.statusCode()).as("Bearer 应通过认证: %s", update.body()).isEqualTo(204);

        JsonNode user = findUser(own);
        assertThat(user.path("attributes").path("phoneNumber").get(0).asText()).isEqualTo("+86 " + target);
        assertThat(user.path("attributes").path("phoneNumberVerified").get(0).asText()).isEqualTo("true");
    }

    @Test
    @DisplayName("update-profile 与 unset：白名单外客户端签发的 Bearer 被拒（403 CLIENT_NOT_ALLOWED），先于请求体解析，号码不变")
    void phoneWritesRejectNonWhitelistedClient() throws Exception {
        String own = "13900000024";
        HttpResponse<String> grant = phoneGrant(own, sendCodeAndAwait("authentication-code", own));
        assertThat(grant.statusCode()).as("取 it-app 签发的用户令牌: %s", grant.body()).isEqualTo(200);
        String bearer = "Bearer " + JSON.readTree(grant.body()).get("access_token").asText();
        String attacker = "13900000025";
        String code = sendCodeAndAwait("verification-code", attacker);

        HttpResponse<String> update = http().send(crossSitePost("/update-profile",
                        form(Map.of("areaCode", "86", "phoneNumber", attacker, "smsCode", code)))
                        .header("Authorization", bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(update.statusCode()).as("it-app 不在白名单内: %s", update.body()).isEqualTo(403);
        assertThat(JSON.readTree(update.body()).get("code").asText()).isEqualTo("CLIENT_NOT_ALLOWED");

        // 非法 JSON 也返回 403 而不是 400：白名单检查先于请求体解析
        HttpResponse<String> json = http().send(HttpRequest.newBuilder(uri("/realms/" + REALM + "/sms/update-profile"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", bearer)
                        .POST(HttpRequest.BodyPublishers.ofString("{not json")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(json.statusCode()).as("JSON 入口: %s", json.body()).isEqualTo(403);
        assertThat(JSON.readTree(json.body()).get("code").asText()).isEqualTo("CLIENT_NOT_ALLOWED");

        HttpResponse<String> unset = http().send(crossSitePost("/update-profile/unset", "")
                        .header("Authorization", bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unset.statusCode()).as("解绑入口: %s", unset.body()).isEqualTo(403);
        assertThat(JSON.readTree(unset.body()).get("code").asText()).isEqualTo("CLIENT_NOT_ALLOWED");

        JsonNode user = findUser(own);
        assertThat(user.path("attributes").path("phoneNumber").get(0).asText()).isEqualTo("+86 " + own);
        assertThat(user.path("attributes").path("phoneNumberVerified").get(0).asText()).isEqualTo("true");
    }

    // ---------- 并发 ----------

    @Test
    @DisplayName("并发提交错误验证码绕不过 5 次上限：实际比对不超过 5 次，之后正确验证码也被拒")
    void concurrentWrongCodesCannotBypassAttemptLimit() throws Exception {
        for (String phone : List.of("13900000031", "13900000032")) {
            String code = sendCodeAndAwait("authentication-code", phone);
            String wrong = code.equals("000000") ? "111111" : "000000";
            int logMark = keycloak.getLogs().length();

            List<Integer> statuses = concurrently(CONCURRENT_GUESSES, () -> phoneGrant(phone, wrong).statusCode());
            assertThat(statuses).as("错误验证码不应签发令牌").doesNotContain(200);

            // 每次比对失败服务端记一行。不加锁时并发请求读到同一个 attempts 各自比对，行数远超上限，
            // 最终 attempts 却仍可能 ≥ 5，只看「之后正确码被拒」区分不了修复前后
            Thread.sleep(1000);
            String failedComparison = "number ending " + phone.substring(phone.length() - 4) + " mismatched";
            long comparisons = keycloak.getLogs().substring(logMark).lines()
                    .filter(line -> line.contains(failedComparison)).count();
            assertThat(comparisons).as("并发 %d 个错误猜测实际比对次数", CONCURRENT_GUESSES)
                    .isLessThanOrEqualTo(MAX_VERIFICATION_ATTEMPTS);

            HttpResponse<String> correct = phoneGrant(phone, code);
            assertThat(correct.statusCode()).as("并发输错 %d 次后正确验证码也应作废: %s", CONCURRENT_GUESSES,
                    correct.body()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("同一号码并发发送只成功一次，其余按冷却中返回 429")
    void concurrentSendsHonourResendCooldown() throws Exception {
        for (String phone : List.of("13900000041", "13900000042")) {
            List<Integer> statuses = concurrently(CONCURRENT_SENDS,
                    () -> sendCode("verification-code", phone).statusCode());
            assertThat(statuses).as("并发发送结果").containsOnly(200, 429);
            assertThat(statuses.stream().filter(status -> status == 200).count())
                    .as("冷却期内只能发出一条: %s", statuses).isEqualTo(1);
        }
    }

    // ---------- 号码输入与发送占位 ----------

    @Test
    @DisplayName("请求号码先校验再规范化：逗号列表、重复区号前缀、换行被拒且不发短信，区号变体共用冷却")
    void invalidPhoneNumbersRejectedAndVariantsShareCooldown() throws Exception {
        String phone = "13900000050";
        int logMark = keycloak.getLogs().length();
        for (String invalid : List.of("13900000070,13900000071", "008613900000050", "+8613900000050",
                "1390000\n0050")) {
            HttpResponse<String> resp = sendCode("verification-code", "86", invalid);
            assertThat(resp.statusCode()).as("%s: %s", invalid, resp.body()).isEqualTo(400);
            assertThat(JSON.readTree(resp.body()).path("code").asText()).isEqualTo("PHONE_NUMBER_INVALID");
        }
        HttpResponse<String> resendQuery = http().send(HttpRequest.newBuilder(uri("/realms/" + REALM
                        + "/sms/verification-code/resend-expires?areaCode=86&phoneNumber=1390000%0AERROR"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resendQuery.statusCode()).as("重发查询同样校验: %s", resendQuery.body()).isEqualTo(400);

        assertThat(sendCode("verification-code", "86", phone).statusCode()).isEqualTo(200);
        assertThat(awaitCodeFromLog(logMark)).as("合法号码应发出").isNotNull();
        for (String areaVariant : List.of("0086", "+86", " 086 ")) {
            HttpResponse<String> variant = sendCode("verification-code", areaVariant, phone);
            assertThat(variant.statusCode()).as("区号 %s 应与 86 共用冷却: %s", areaVariant, variant.body())
                    .isEqualTo(429);
        }
        assertThat(CODE_IN_LOG.matcher(keycloak.getLogs().substring(logMark)).results().count())
                .as("被拒的请求都不应调用短信服务商").isEqualTo(1);
    }

    @Test
    @DisplayName("验证码被成功消费后不再被发送占位挡住，冷却期内即可重发")
    void resendAllowedRightAfterCodeConsumed() throws Exception {
        String phone = "13900000051";
        String code = sendCodeAndAwait("authentication-code", phone);
        HttpResponse<String> grant = phoneGrant(phone, code);
        assertThat(grant.statusCode()).as("Everybody 直授应消费验证码: %s", grant.body()).isEqualTo(200);

        // 冷却是 60 秒；占位只撑过发送那次提交，消费后最多等这么久加余量就能重发
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(SEND_RESERVATION_AFTER_SUCCESS_SECONDS + 5L);
        HttpResponse<String> resend = sendAuthenticationCode(phone);
        while (resend.statusCode() != 200 && System.nanoTime() < deadline) {
            Thread.sleep(500);
            resend = sendAuthenticationCode(phone);
        }
        assertThat(resend.statusCode()).as("消费后应能重发: %s", resend.body()).isEqualTo(200);
    }

    private static JsonNode findUser(String username) throws Exception {
        JsonNode users = JSON.readTree(adminGet("/admin/realms/" + REALM + "/users?exact=true&username=" + username,
                adminToken()).body());
        assertThat(users).hasSize(1);
        return users.get(0);
    }

    private static List<String> credentialTypes(JsonNode user) throws Exception {
        return JSON.readTree(adminGet("/admin/realms/" + REALM + "/users/" + user.get("id").asText() + "/credentials",
                adminToken()).body()).findValuesAsText("type");
    }

    // ---------- realm setup ----------

    private static void importRealm() throws Exception {
        String realm = """
                {
                  "realm": "%1$s",
                  "enabled": true,
                  "sslRequired": "none",
                  "loginTheme": "phone-it",
                  "browserFlow": "sms-browser",
                  "registrationAllowed": true,
                  "registrationFlow": "phone-registration",
                  "requiredActions": [{
                    "alias": "CONFIGURE_SMS_OTP", "name": "Configure SMS OTP", "providerId": "CONFIGURE_SMS_OTP",
                    "enabled": true, "defaultAction": false, "priority": 100, "config": {}
                  }],
                  "directGrantFlow": "everybody-direct",
                  "clients": [{
                    "clientId": "%2$s",
                    "publicClient": true,
                    "standardFlowEnabled": true,
                    "directAccessGrantsEnabled": true,
                    "redirectUris": ["http://localhost/*"]
                  }],
                  "authenticationFlows": [
                    {
                      "alias": "sms-browser", "providerId": "basic-flow", "topLevel": true, "builtIn": false,
                      "authenticationExecutions": [
                        {"authenticator": "auth-username-password-form", "requirement": "REQUIRED", "priority": 10,
                         "authenticatorFlow": false, "userSetupAllowed": false},
                        {"authenticator": "sms-otp-authenticator", "requirement": "REQUIRED", "priority": 20,
                         "authenticatorFlow": false, "userSetupAllowed": true}
                      ]
                    },
                    {
                      "alias": "phone-registration", "providerId": "basic-flow", "topLevel": true, "builtIn": false,
                      "authenticationExecutions": [
                        {"authenticator": "registration-page-form", "requirement": "REQUIRED", "priority": 10,
                         "authenticatorFlow": true, "flowAlias": "phone-registration-form", "userSetupAllowed": false}
                      ]
                    },
                    {
                      "alias": "phone-registration-form", "providerId": "form-flow", "topLevel": false, "builtIn": false,
                      "authenticationExecutions": [
                        {"authenticator": "registration-user-creation", "requirement": "REQUIRED", "priority": 10,
                         "authenticatorFlow": false, "userSetupAllowed": false},
                        {"authenticator": "registration-phone", "requirement": "REQUIRED", "priority": 20,
                         "authenticatorFlow": false, "userSetupAllowed": false},
                        {"authenticator": "registration-password-action", "requirement": "REQUIRED", "priority": 30,
                         "authenticatorFlow": false, "userSetupAllowed": false}
                      ]
                    },
                    {
                      "alias": "everybody-direct", "providerId": "basic-flow", "topLevel": true, "builtIn": false,
                      "authenticationExecutions": [
                        {"authenticator": "everybody-phone-authenticator", "requirement": "REQUIRED", "priority": 10,
                         "authenticatorFlow": false, "userSetupAllowed": false}
                      ]
                    }
                  ],
                  "users": [%3$s, %4$s, %5$s, %6$s, %7$s, %8$s, %9$s, %10$s, %11$s]
                }
                """.formatted(REALM, CLIENT_ID,
                user("alice", "13900000001"), user("bob", "13900000002"), user("carol", "13900000003"),
                user("dave", "13900000004"), user("erin", "13900000005"),
                passwordOnlyUser("grace", null), passwordOnlyUser("henry", "13900000008"),
                user("ivan", "13900000010"), user("kate", "13900000011"));

        HttpResponse<String> resp = http().send(HttpRequest.newBuilder(uri("/admin/realms"))
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(realm))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("导入测试 realm: %s", resp.body()).isEqualTo(201);

        // 允许管理员 REST 读取插件直接写入的 phoneNumber / phoneNumberVerified（未在 User Profile 中声明）
        String token = adminToken();
        ObjectNode profile = (ObjectNode)
                JSON.readTree(adminGet("/admin/realms/" + REALM + "/users/profile", token).body());
        profile.put("unmanagedAttributePolicy", "ADMIN_VIEW");
        HttpResponse<String> updated = http().send(HttpRequest.newBuilder(uri("/admin/realms/" + REALM + "/users/profile"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(profile.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).as("更新 User Profile: %s", updated.body()).isEqualTo(200);

        // 号码写接口默认只认 account-console 签发的令牌；给它开直授，才能像 it-app 一样用短信直授取令牌
        enableDirectAccessGrants(ACCOUNT_CONSOLE, token);
    }

    private static void enableDirectAccessGrants(String clientId, String token) throws Exception {
        JsonNode clients = JSON.readTree(
                adminGet("/admin/realms/" + REALM + "/clients?clientId=" + clientId, token).body());
        assertThat(clients).as("realm 中应有客户端 %s", clientId).hasSize(1);
        ObjectNode client = (ObjectNode) clients.get(0);
        client.put("directAccessGrantsEnabled", true);
        HttpResponse<String> resp = http().send(HttpRequest.newBuilder(
                                uri("/admin/realms/" + REALM + "/clients/" + client.get("id").asText()))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(client.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("开启 %s 直授: %s", clientId, resp.body()).isEqualTo(204);
    }

    private static HttpResponse<String> adminGet(String path, String token) throws Exception {
        return http().send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String user(String username, String phone) {
        return """
                {
                  "username": "%1$s", "enabled": true,
                  "email": "%1$s@example.com", "emailVerified": true, "firstName": "%1$s", "lastName": "it",
                  "attributes": {"phoneNumber": ["+86 %2$s"], "phoneNumberVerified": ["true"]},
                  "credentials": [
                    {"type": "password", "value": "%1$s-pass", "temporary": false},
                    {"type": "phone-otp", "userLabel": "sms",
                     "credentialData": "{\\"areaCode\\":\\"86\\",\\"phoneNumber\\":\\"%2$s\\"}", "secretData": "{}"}
                  ]
                }
                """.formatted(username, phone);
    }

    /** 只有密码凭据、尚未配置短信 MFA 的用户；phone 为 null 表示没有号码，否则为已验证号码。 */
    private static String passwordOnlyUser(String username, String phone) {
        String attributes = phone == null ? "{}"
                : "{\"phoneNumber\": [\"+86 " + phone + "\"], \"phoneNumberVerified\": [\"true\"]}";
        return """
                {
                  "username": "%1$s", "enabled": true,
                  "email": "%1$s@example.com", "emailVerified": true, "firstName": "%1$s", "lastName": "it",
                  "attributes": %2$s,
                  "credentials": [{"type": "password", "value": "%1$s-pass", "temporary": false}]
                }
                """.formatted(username, attributes);
    }

    private static String adminToken() throws Exception {
        HttpResponse<String> resp = http().send(HttpRequest.newBuilder(uri("/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "grant_type", "password",
                                "client_id", "admin-cli",
                                "username", keycloak.getAdminUsername(),
                                "password", keycloak.getAdminPassword()))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(resp.body());
        return body.get("access_token").asText();
    }

    // ---------- helpers ----------

    private static HttpResponse<String> sendAuthenticationCode(String phone) throws Exception {
        return sendCode("authentication-code", phone);
    }

    private static HttpResponse<String> sendCode(String endpoint, String phone) throws Exception {
        return sendCode(endpoint, "86", phone);
    }

    private static HttpResponse<String> sendCode(String endpoint, String areaCode, String phone) throws Exception {
        return http().send(HttpRequest.newBuilder(uri("/realms/" + REALM + "/sms/" + endpoint))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("areaCode", areaCode, "phoneNumber", phone))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 发送验证码并从 dummy sender 日志里取回。 */
    private static String sendCodeAndAwait(String endpoint, String phone) throws Exception {
        int logMark = keycloak.getLogs().length();
        HttpResponse<String> sent = sendCode(endpoint, phone);
        assertThat(sent.statusCode()).as("发送 %s 到 %s: %s", endpoint, phone, sent.body()).isEqualTo(200);
        String code = awaitCodeFromLog(logMark);
        assertThat(code).as("应能从日志中捕获验证码").isNotNull();
        return code;
    }

    /** 跨站来源的号码接口表单 POST，路径相对 {@code /realms/{realm}/sms}。 */
    private static HttpRequest.Builder crossSitePost(String smsPath, String body) {
        return HttpRequest.newBuilder(uri("/realms/" + REALM + "/sms" + smsPath))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", EVIL_ORIGIN)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    /** 完成密码 + 短信两步登录，返回持有登录 Cookie 的浏览器。 */
    private static Browser loginWithSms(String username, String password) throws Exception {
        Browser browser = new Browser();
        int logMark = keycloak.getLogs().length();
        HttpResponse<String> otpPage = browser.loginWithPassword(username, password);
        assertThat(otpPage.body()).as("应进入短信验证码页面: %s", describe(otpPage)).contains("kc-sms-otp-form");
        String code = awaitCodeFromLog(logMark);
        assertThat(code).isNotNull();
        HttpResponse<String> done = browser.submitForm(otpPage.body(), Map.of("smsCode", code));
        assertThat(done.statusCode()).as("正确验证码应完成登录: %s", describe(done)).isEqualTo(302);
        return browser;
    }

    /** 同时放出 {@code count} 个请求（先全部就绪再一起开始），按提交顺序返回结果。 */
    private static <T> List<T> concurrently(int count, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(2, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static HttpResponse<String> phoneGrant(String phone, String code) throws Exception {
        return phoneGrant(CLIENT_ID, phone, code);
    }

    private static HttpResponse<String> phoneGrant(String clientId, String phone, String code) throws Exception {
        return http().send(HttpRequest.newBuilder(uri("/realms/" + REALM + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "grant_type", "password",
                                "client_id", clientId,
                                "areaCode", "86",
                                "phoneNumber", phone,
                                "smsCode", code))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 带独立 Cookie 存储、不自动跟随重定向的浏览器模拟。
     * <p>
     * 手动维护 Cookie：Keycloak 的会话 Cookie 带 {@code Secure}，{@link java.net.CookieManager} 在 http 下不会回传。
     */
    private static final class Browser {
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private final HttpClient client = http();

        void addCookie(String name, String value) {
            cookies.put(name, value);
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        /** 跟随 Keycloak 内部的 login-actions 跳转（如进入必需操作页），不跟随回到客户端的重定向。 */
        HttpResponse<String> followLoginActions(HttpResponse<String> resp) throws Exception {
            String location = resp.headers().firstValue("Location").orElse("");
            while (resp.statusCode() == 302 && location.contains("/login-actions/")) {
                resp = send(HttpRequest.newBuilder(URI.create(location)).GET());
                location = resp.headers().firstValue("Location").orElse("");
            }
            return resp;
        }

        HttpResponse<String> loginWithPassword(String username, String password) throws Exception {
            String authUrl = "/realms/" + REALM + "/protocol/openid-connect/auth?client_id=" + CLIENT_ID
                    + "&response_type=code&scope=openid&redirect_uri="
                    + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8);
            HttpResponse<String> loginPage = send(HttpRequest.newBuilder(uri(authUrl)).GET());
            assertThat(loginPage.statusCode()).as("登录页: %s", describe(loginPage)).isEqualTo(200);
            return submitForm(loginPage.body(), Map.of("username", username, "password", password));
        }

        HttpResponse<String> submitForm(String html, Map<String, String> fields) throws Exception {
            Matcher m = FORM_ACTION.matcher(html);
            assertThat(m.find()).as("页面中应有表单: %s", html).isTrue();
            String action = m.group(1).replace("&amp;", "&");
            return send(HttpRequest.newBuilder(URI.create(action))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(fields))));
        }

        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            if (!cookies.isEmpty()) {
                request.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            HttpResponse<String> resp = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : resp.headers().allValues("Set-Cookie")) {
                String[] pair = header.split(";", 2)[0].split("=", 2);
                if (header.contains("Max-Age=0") || pair.length < 2 || pair[1].isEmpty()) {
                    cookies.remove(pair[0]);
                } else {
                    cookies.put(pair[0], pair[1]);
                }
            }
            return resp;
        }
    }

    private static HttpClient http() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String extractSetCookie(HttpResponse<?> resp, String name) {
        Optional<String> header = resp.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .findFirst();
        return header.map(h -> h.substring(name.length() + 1).split(";", 2)[0]).orElse(null);
    }

    private static String describe(HttpResponse<String> resp) {
        String body = resp.body();
        return resp.statusCode() + " " + resp.headers().firstValue("Location").orElse("")
                + " " + (body.length() > 500 ? body.substring(0, 500) : body);
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** 从指定偏移之后的容器日志中轮询捕获 dummy sender 打印的验证码。 */
    private static String awaitCodeFromLog(int fromIndex) throws InterruptedException {
        for (int i = 0; i < 25; i++) {
            String logs = keycloak.getLogs();
            if (logs.length() > fromIndex) {
                Matcher m = CODE_IN_LOG.matcher(logs.substring(fromIndex));
                if (m.find()) {
                    return m.group(1);
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static URI uri(String path) {
        return URI.create(keycloak.getAuthServerUrl() + path);
    }

    private static List<File> locateProviderJars() {
        Path dir = Path.of(System.getProperty("providers.dir", "../target/providers"))
                .toAbsolutePath().normalize();
        List<File> jars = List.of(
                dir.resolve("keycloak-phone-provider.jar").toFile(),
                dir.resolve("keycloak-sms-provider-dummy.jar").toFile());
        for (File jar : jars) {
            if (!jar.isFile()) {
                throw new IllegalStateException(
                        "找不到 provider jar: " + jar + "。请先运行 `mvn -Pit clean package` 构建 provider。");
            }
        }
        return jars;
    }
}
