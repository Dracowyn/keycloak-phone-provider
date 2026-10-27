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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2.2.0 安全修复的回归测试，在真实 Keycloak 容器中验证：
 * <ul>
 *   <li>伪造 / 他人的 {@code SMS_OTP_ANSWERED} Cookie 不能跳过短信第二因子，合法签发的 Cookie 仍可免验证；</li>
 *   <li>Everybody 直授流程中同一验证码不能重放；</li>
 *   <li>连续输错 5 次只作废当前验证码，不清空重发冷却；</li>
 *   <li>注册表单 validate 阶段只校验不消费，其他字段出错时验证码仍可用，注册成功后号码被标记为已验证。</li>
 * </ul>
 */
class SecurityFixesIT {

    private static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.8.1";
    private static final String REALM = "sec";
    private static final String CLIENT_ID = "it-app";
    private static final String REDIRECT_URI = "http://localhost/cb";
    private static final String COOKIE_NAME = "SMS_OTP_ANSWERED";
    private static final Pattern CODE_IN_LOG = Pattern.compile(">>>\\s*([0-9]{4,8})");
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");
    private static final ObjectMapper JSON = new ObjectMapper();

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
                         "authenticatorFlow": false, "userSetupAllowed": false}
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
                  "users": [%3$s, %4$s, %5$s, %6$s, %7$s]
                }
                """.formatted(REALM, CLIENT_ID,
                user("alice", "13900000001"), user("bob", "13900000002"), user("carol", "13900000003"),
                user("dave", "13900000004"), user("erin", "13900000005"));

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
        return http().send(HttpRequest.newBuilder(uri("/realms/" + REALM + "/sms/" + endpoint))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("areaCode", "86", "phoneNumber", phone))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> phoneGrant(String phone, String code) throws Exception {
        return http().send(HttpRequest.newBuilder(uri("/realms/" + REALM + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "grant_type", "password",
                                "client_id", CLIENT_ID,
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
