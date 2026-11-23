package cc.coopersoft.keycloak.phone.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak Phone Provider 集成测试。
 *
 * <p>在真实的 Keycloak {@value #DEFAULT_IMAGE} 容器中部署 provider jar，验证：
 * <ol>
 *   <li>所有自定义 SPI / JPA 实体 / Liquibase changelog 能在目标 Keycloak 版本下正常加载（容器成功启动即证明）；</li>
 *   <li>{@code GET /realms/master/sms} 配置接口可用（RealmResourceProvider + ConfigService + AreaCodeService 装配）；</li>
 *   <li>{@code POST /realms/master/sms/verification-code} 发送验证码并落库（PhoneMessageService + persistCode 写入 JPA 实体）；</li>
 *   <li>{@code GET .../resend-expires} 读回落库记录（currentProcess 命名查询 + LocalDateTime 绑定的持久层往返）。</li>
 * </ol>
 *
 * <p>发送短信使用 dummy provider（默认 senderService=dummy），它会把验证码打到容器日志，
 * 供测试断言使用；生产用的核心发送路径已不再记录验证码明文。
 *
 * <p>容器部署了 geetest 人机验证模块但不配置 id / key：人机验证一律不通过（fail-closed），
 * 发送类用例以受信机密客户端（{@value #BACKEND_CLIENT_ID}）的密钥走豁免路径。
 */
class PhoneProviderIT {

    private static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.8.2";
    private static final Pattern CODE_IN_LOG = Pattern.compile(">>>\\s*([0-9]{4,8})");
    /** 受信后端客户端：已启用的机密客户端，凭密钥可免人机验证。 */
    private static final String BACKEND_CLIENT_ID = "phone-it-backend";
    private static final String BACKEND_CLIENT_SECRET = "phone-it-backend-secret";

    private static KeycloakContainer keycloak;
    private static HttpClient http;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void startKeycloak() throws Exception {
        String image = System.getProperty("keycloak.image", DEFAULT_IMAGE);
        List<File> providerJars = locateProviderJars();

        keycloak = new KeycloakContainer(image)
                .withProviderLibsFrom(providerJars)
                // ConfigService 默认从工作目录 ./areacode.json 读取区号配置；
                // Keycloak 容器的工作目录是 /，因此把文件放到 /areacode.json。
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("areacode.json"),
                        "/areacode.json")
                .withStartupTimeout(Duration.ofMinutes(5));
        keycloak.start();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        createBackendClient();
    }

    @AfterAll
    static void stopKeycloak() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    @Test
    @DisplayName("容器能带着全部 provider 在目标 Keycloak 版本下正常启动（SPI/JPA/Liquibase 加载）")
    void containerStartsWithProviders() {
        // 若任一 SPI、JPA 实体或 changelog 在目标版本下加载失败，start-dev 的构建会失败、容器无法就绪，
        // startKeycloak() 会直接抛异常。因此“容器处于运行态”本身就是最有价值的运行时冒烟断言。
        assertThat(keycloak.isRunning()).isTrue();
    }

    @Test
    @DisplayName("GET /realms/master/sms 返回短信配置")
    void getSmsConfigReturnsConfig() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/realms/master/sms")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.get("tokenExpires").asInt()).isEqualTo(300);
        assertThat(body.get("defaultAreaCode").asText()).isEqualTo("86");
        assertThat(body.get("areaCodeList")).isNotNull();
        assertThat(body.get("areaCodeList").isArray()).isTrue();
        assertThat(body.get("areaCodeList")).isNotEmpty();
    }

    @Test
    @DisplayName("POST /sms/verification-code 发送验证码、落库并返回过期时间")
    void sendVerificationCodePersistsAndReturnsExpiry() throws Exception {
        String phone = "13800138001";
        int logMark = keycloak.getLogs().length();

        HttpResponse<String> resp = sendVerificationCode("86", phone);

        assertThat(resp.statusCode())
                .as("发送应成功，实际响应: %s", resp.body())
                .isEqualTo(200);

        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.get("expiresIn").asLong()).isPositive();
        assertThat(body.get("resendExpires").asLong()).isPositive();

        // dummy sender 把验证码打到容器日志：确认确实生成并“发送”了一个 4~8 位数字验证码
        String code = awaitCodeFromLog(logMark);
        assertThat(code).as("应能从 dummy sender 日志中捕获验证码").isNotNull();
        assertThat(code).matches("[0-9]{4,8}");
    }

    @Test
    @DisplayName("已部署 geetest 但缺 id / key：不带受信客户端密钥的发送被拒（CAPTCHA_REQUIRED），启动时打 ERROR")
    void unconfiguredCaptchaRejectsSend() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/realms/master/sms/verification-code"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("areaCode=86&phoneNumber=13800138003"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).as("实际响应: %s", resp.body()).isEqualTo(400);
        assertThat(JSON.readTree(resp.body()).get("code").asText()).isEqualTo("CAPTCHA_REQUIRED");
        // 区别于 verify 时的日志：这条只在 factory init 时打出
        assertThat(keycloak.getLogs()).contains("极验配置不完整，缺少配置键 [id, key]；补齐前人机验证一律不通过");
    }

    @Test
    @DisplayName("发送后 GET .../resend-expires 能读回落库记录（LocalDateTime 持久层往返）")
    void resendExpiresReflectsPersistedCode() throws Exception {
        String phone = "13800138002";
        // 先发送，写入一条 TokenCode 记录
        assertThat(sendVerificationCode("86", phone).statusCode()).isEqualTo(200);

        // 读回：currentProcess 命名查询会用 LocalDateTime :now 与 expiresAt 比较，
        // 若 Date→LocalDateTime 迁移破坏了映射或查询，这里会 4xx/5xx。
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(
                                uri("/realms/master/sms/verification-code/resend-expires"
                                        + "?areaCode=86&phoneNumber=" + phone))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as("读取 resend-expires 应成功，实际响应: %s", resp.body())
                .isEqualTo(200);
        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.get("resendExpire").asLong()).isPositive();
    }

    // ---------- helpers ----------

    /** 以受信后端客户端身份发送（geetest 未配置，人机验证本身一律不通过）。 */
    private static HttpResponse<String> sendVerificationCode(String areaCode, String phone) throws Exception {
        String form = "areaCode=" + areaCode + "&phoneNumber=" + phone
                + "&client_id=" + BACKEND_CLIENT_ID + "&client_secret=" + BACKEND_CLIENT_SECRET;
        HttpRequest req = HttpRequest.newBuilder(uri("/realms/master/sms/verification-code"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
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

    /** 在 master realm 建一个已启用的机密客户端，作为可免人机验证的受信后端。 */
    private static void createBackendClient() throws Exception {
        HttpResponse<String> tokenResp = http.send(
                HttpRequest.newBuilder(uri("/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli"
                                + "&username=" + keycloak.getAdminUsername()
                                + "&password=" + keycloak.getAdminPassword()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String adminToken = JSON.readTree(tokenResp.body()).get("access_token").asText();

        String client = """
                {"clientId": "%s", "enabled": true, "publicClient": false,
                 "clientAuthenticatorType": "client-secret", "secret": "%s",
                 "standardFlowEnabled": false, "directAccessGrantsEnabled": false}
                """.formatted(BACKEND_CLIENT_ID, BACKEND_CLIENT_SECRET);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(uri("/admin/realms/master/clients"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(client))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("创建受信后端客户端: %s", resp.body()).isEqualTo(201);
    }

    private static URI uri(String path) {
        return URI.create(keycloak.getAuthServerUrl() + path);
    }

    /** 定位父反应堆构建产出的 provider jar（核心 + dummy 短信 + geetest 人机验证）。 */
    private static List<File> locateProviderJars() {
        Path dir = Path.of(System.getProperty("providers.dir", "../target/providers"))
                .toAbsolutePath().normalize();
        List<File> jars = List.of(
                dir.resolve("keycloak-phone-provider.jar").toFile(),
                dir.resolve("keycloak-sms-provider-dummy.jar").toFile(),
                dir.resolve("keycloak-captcha-provider-geetest.jar").toFile());
        for (File jar : jars) {
            if (!jar.isFile()) {
                throw new IllegalStateException(
                        "找不到 provider jar: " + jar + "。请先运行 `mvn -Pit clean package` 构建 provider。");
            }
        }
        return jars;
    }
}
