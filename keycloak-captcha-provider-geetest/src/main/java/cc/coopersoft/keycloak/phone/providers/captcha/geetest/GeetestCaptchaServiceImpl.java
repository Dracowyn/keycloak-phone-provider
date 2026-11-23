package cc.coopersoft.keycloak.phone.providers.captcha.geetest;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geetest.sdk.GeetestLib;
import com.geetest.sdk.GeetestLibResult;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;

import jakarta.ws.rs.core.MultivaluedMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 极验人机验证服务实现
 * 支持极验3.0和4.0两个版本的验证
 */
public class GeetestCaptchaServiceImpl implements CaptchaService {
    private static final Logger log = Logger.getLogger(GeetestCaptchaServiceImpl.class);
    private static final String DEFAULT_VERSION = "3";
    private static final String DEFAULT_USER_ID = "guest";
    private static final String UNKNOWN_USER = "unknown";
    private static final String V4_API_DOMAIN = "https://gcaptcha4.geetest.com";

    /**
     * 极验 v4 校验接口的连接超时（毫秒）。不设超时时网络卡住会一直占着登录请求线程。
     */
    private static final int V4_CONNECT_TIMEOUT_MILLIS = 3000;

    /**
     * 极验 v4 校验接口的读取超时（毫秒），与 v3 SDK 的 5 秒一致。
     */
    private static final int V4_READ_TIMEOUT_MILLIS = 5000;

    // 极验3.0宕机模式按 challenge 登记：只有本服务在 register 失败时本地生成并登记过的 challenge，
    // 才允许走 failValidate（仅校验非空）。不能用进程级共享状态，否则一个用户初始化失败会让所有用户的校验被放行。
    // 宕机模式只在显式配置 fallback_on_error=true 时启用，默认 register 失败即无法通过人机验证。
    private static final String OFFLINE_CHALLENGE_KEY_PREFIX = "geetest-v3-offline:";
    private static final long OFFLINE_CHALLENGE_LIFESPAN_SECONDS = 10 * 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 必填配置键：缺任一都无法校验，此时人机验证一律不通过。
     */
    private static final List<String> REQUIRED_CONFIG_KEYS = List.of("id", "key");

    /**
     * 极验服务不可用时是否放行的配置键，v3 宕机模式与 v4 服务故障共用，默认不放行。
     */
    private static final String CONFIG_FALLBACK_ON_ERROR = "fallback_on_error";

    private final KeycloakSession session;
    @Setter
    private Config.Scope config;

    /**
     * 极验 v4 接口地址，仅供测试替换为本地桩服务。
     */
    @Setter(AccessLevel.PACKAGE)
    private String v4ApiDomain = V4_API_DOMAIN;

    public GeetestCaptchaServiceImpl(KeycloakSession session) {
        this.session = session;
    }

    /**
     * 根据认证结果获取用户ID
     *
     * @param user 认证结果
     * @return 用户ID，如果为空则返回默认用户ID
     */
    private String getUserIdByAuthResult(AuthenticationManager.AuthResult user) {
        return user != null ? user.user().getId() : DEFAULT_USER_ID;
    }

    /**
     * 列出未配置（或为空白）的必填配置键。只返回键名，调用方据此打日志时不会带出任何密钥值。
     *
     * @param config 本 provider 的配置
     * @return 缺失的键名，配置齐全时为空列表
     */
    static List<String> missingConfigKeys(Config.Scope config) {
        return REQUIRED_CONFIG_KEYS.stream()
                .filter(key -> {
                    String value = config.get(key);
                    return value == null || value.isBlank();
                })
                .toList();
    }

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, AuthenticationManager.AuthResult user) {
        return verify(formParams, getUserIdByAuthResult(user));
    }

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, String user) {
        // 确保用户ID不为空
        user = Optional.ofNullable(user).orElse(UNKNOWN_USER);

        // 配置不全时无法校验，按不通过处理（部署了就必须生效，不能静默放行）
        List<String> missingKeys = missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("极验配置不完整，缺少配置键 %s，人机验证不通过", missingKeys);
            return false;
        }

        // 获取极验配置
        String geetestId = config.get("id");
        String geetestKey = config.get("key");

        // 获取极验版本，默认为3.0
        String geetestVersion = Optional.ofNullable(config.get("version")).orElse(DEFAULT_VERSION);

        // 根据不同版本进行验证
        if ("4".equals(geetestVersion)) {
            return verifyV4(formParams, geetestId, geetestKey);
        } else {
            return verifyV3(formParams, user, geetestId, geetestKey);
        }
    }

    /**
     * 验证极验3.0版本
     *
     * @param formParams 表单参数
     * @param user       用户ID
     * @param geetestId  极验ID
     * @param geetestKey 极验密钥
     * @return 验证是否成功
     */
    private boolean verifyV3(MultivaluedMap<String, String> formParams, String user, String geetestId, String geetestKey) {
        // 获取必要参数
        String challenge = formParams.getFirst(GeetestLib.GEETEST_CHALLENGE);
        String validate = formParams.getFirst(GeetestLib.GEETEST_VALIDATE);
        String seccode = formParams.getFirst(GeetestLib.GEETEST_SECCODE);

        if (challenge == null || validate == null || seccode == null) {
            log.warn("表单提交中缺少必要的极验参数");
            return false;
        }

        GeetestLib gtLib = new GeetestLib(geetestId, geetestKey);
        GeetestLibResult result;

        // 根据该 challenge 初始化时的服务状态选择验证方式；宕机登记一次性消费，不可重复使用
        if (session.singleUseObjects().remove(offlineChallengeKey(challenge)) != null) {
            if (!isFallbackOnErrorEnabled()) {
                // 登记于开关关闭之前的宕机 challenge：关闭后同样不放行
                log.warn("极验v3宕机模式的 challenge，未开启 fallback_on_error，人机验证不通过");
                return false;
            }
            result = gtLib.failValidate(challenge, validate, seccode);
        } else {
            Map<String, String> paramMap = createParamMap(user);
            result = gtLib.successValidate(challenge, validate, seccode, paramMap);
        }
        return result.getStatus() == 1;
    }

    /**
     * 验证极验4.0版本
     *
     * @param formParams 表单参数
     * @param geetestId  极验ID
     * @param geetestKey 极验密钥
     * @return 验证是否成功
     */
    private boolean verifyV4(MultivaluedMap<String, String> formParams, String geetestId, String geetestKey) {
        // 获取v4所需参数
        String captchaOutput = formParams.getFirst("captcha_output");
        String genTime = formParams.getFirst("gen_time");
        String lotNumber = formParams.getFirst("lot_number");
        String passToken = formParams.getFirst("pass_token");

        if (captchaOutput == null || genTime == null || lotNumber == null || passToken == null) {
            log.warn("表单提交中缺少必要的极验4.0参数");
            return false;
        }

        // 生成签名
        String signToken = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, geetestKey).hmacHex(lotNumber);

        // 构建请求参数
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("lot_number", lotNumber);
        queryParams.put("captcha_output", captchaOutput);
        queryParams.put("pass_token", passToken);
        queryParams.put("gen_time", genTime);
        queryParams.put("sign_token", signToken);

        String url = String.format("%s/validate?captcha_id=%s", v4ApiDomain, geetestId);

        // 构建请求体
        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> param : queryParams.entrySet()) {
            if (!postData.isEmpty()) {
                postData.append('&');
            }
            postData.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8));
            postData.append('=');
            postData.append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(V4_CONNECT_TIMEOUT_MILLIS);
            conn.setReadTimeout(V4_READ_TIMEOUT_MILLIS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            int status = sendRequest(conn, postData);
            if (status >= 500) {
                // 极验服务端故障，与连不上同属服务不可用
                return allowOnServiceFailure("极验v4 API返回HTTP " + status, null);
            }
            if (status < 200 || status >= 300) {
                // 4xx 等说明请求被拒（参数由请求方提交、可被构造），任何配置下都不放行
                log.warnf("极验v4 API拒绝了校验请求（HTTP %d），人机验证不通过", status);
                return false;
            }

            String responseBody = readResponse(conn);
            log.debug("极验v4 API响应: " + responseBody);

            // 解析响应结果
            if (responseBody.contains("\"result\":\"success\"")) {
                return true;
            }

            log.warn("极验v4验证失败: " + responseBody);
            return false;

        } catch (IOException e) {
            // 连接失败、超时、DNS 解析失败等网络类异常
            return allowOnServiceFailure("连接极验v4 API时出错: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("处理极验v4校验时出错，人机验证不通过", e);
            return false;
        }
    }

    /**
     * 极验服务不可用（网络、超时类异常或服务端 5xx）时的处理：只有显式配置 {@code fallback_on_error=true} 才放行。
     * <p>
     * 默认不放行：攻击者能让校验请求出错（例如构造被拒的参数）时，默认放行等于没有人机验证。
     */
    private boolean allowOnServiceFailure(String reason, Exception cause) {
        boolean fallback = isFallbackOnErrorEnabled();
        if (fallback) {
            log.warnf(cause, "%s；已按 fallback_on_error=true 放行", reason);
        } else {
            log.warnf(cause, "%s；人机验证不通过（未开启 fallback_on_error）", reason);
        }
        return fallback;
    }

    /**
     * 是否显式配置了 {@code fallback_on_error=true}。
     */
    private boolean isFallbackOnErrorEnabled() {
        return Boolean.TRUE.equals(config.getBoolean(CONFIG_FALLBACK_ON_ERROR));
    }

    /**
     * 发送HTTP请求并取得状态码
     *
     * @param conn     HTTP连接
     * @param postData 请求数据
     * @return HTTP 状态码
     * @throws IOException 如果发生IO错误
     */
    private static int sendRequest(HttpURLConnection conn, StringBuilder postData) throws IOException {
        // 发送请求数据
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = postData.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        return conn.getResponseCode();
    }

    /**
     * 读取 2xx 响应的内容
     *
     * @param conn HTTP连接
     * @return 响应内容
     * @throws IOException 如果发生IO错误
     */
    private static String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        return response.toString();
    }

    /**
     * 创建参数映射
     *
     * @param user 用户ID
     * @return 参数映射
     */
    private Map<String, String> createParamMap(String user) {
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("user_id", user);
        paramMap.put("client_type", "web");
        paramMap.put("ip_address", session.getContext().getConnection().getRemoteAddr());
        return paramMap;
    }

    @Override
    public String getFrontendKey(AuthenticationManager.AuthResult user) {
        return getFrontendKey(getUserIdByAuthResult(user));
    }

    @Override
    public String getFrontendKey(String user) {
        // 确保用户ID不为空
        user = Optional.ofNullable(user).orElse(UNKNOWN_USER);

        // 与 verify 用同一套必填检查：空白也算缺失
        List<String> missingKeys = missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("极验配置不完整，缺少配置键 %s，无法提供前端配置", missingKeys);
            return "{\"success\":0,\"message\":\"极验ID或密钥未配置\"}";
        }

        // 获取极验配置
        String geetestId = config.get("id");
        String geetestKey = config.get("key");

        // 获取极验版本，默认为3.0
        String geetestVersion = Optional.ofNullable(config.get("version")).orElse(DEFAULT_VERSION);

        // 如果是4.0版本，直接返回ID
        if ("4".equals(geetestVersion)) {
            return "{\"success\":1,\"gt\":\"" + geetestId + "\"}";
        }

        // 如果是3.0版本，返回验证结果
        return registerResultForFrontend(getGeetestLibResult(user, geetestId, geetestKey));
    }

    /**
     * 处理 v3 register 结果并返回给前端的数据，响应结构不变。
     * <p>
     * register 失败（出错、超时、返回 0）时，只有开启 {@code fallback_on_error} 才把下发的 challenge 登记为宕机模式，
     * 之后按宕机模式校验；未开启时不登记，该 challenge 走正常二次校验，极验不可用时自然不通过。
     *
     * @param result register 结果
     * @return 给前端的 JSON
     */
    String registerResultForFrontend(GeetestLibResult result) {
        if (result.getStatus() != 1) {
            if (!isFallbackOnErrorEnabled()) {
                log.warn("极验v3初始化失败，未开启 fallback_on_error，不启用宕机模式，本次人机验证无法通过");
                return result.getData();
            }
            String challenge = extractChallenge(result.getData());
            if (challenge != null) {
                log.warn("极验v3初始化失败，已按 fallback_on_error=true 登记宕机模式 challenge");
                session.singleUseObjects().put(offlineChallengeKey(challenge), OFFLINE_CHALLENGE_LIFESPAN_SECONDS, Map.of("offline", "1"));
            }
        }
        return result.getData();
    }

    static String offlineChallengeKey(String challenge) {
        return OFFLINE_CHALLENGE_KEY_PREFIX + challenge;
    }

    /**
     * 从 register 返回的 JSON 中取出 challenge，缺失或解析失败时返回 {@code null}。
     */
    static String extractChallenge(String registerData) {
        if (registerData == null || registerData.isEmpty()) {
            return null;
        }
        try {
            JsonNode challenge = MAPPER.readTree(registerData).get("challenge");
            return challenge == null || challenge.asText().isEmpty() ? null : challenge.asText();
        } catch (JsonProcessingException e) {
            log.warn("解析极验初始化结果失败", e);
            return null;
        }
    }

    /**
     * 获取极验库结果
     *
     * @param user       用户ID
     * @param geetestId  极验ID
     * @param geetestKey 极验密钥
     * @return 极验库结果
     */
    private GeetestLibResult getGeetestLibResult(String user, String geetestId, String geetestKey) {
        GeetestLib gtLib = new GeetestLib(geetestId, geetestKey);
        String digestmod = "md5";

        Map<String, String> paramMap = createParamMap(user);
        paramMap.put("digestmod", digestmod);

        return gtLib.register(digestmod, paramMap);
    }

    @Override
    public void close() {
    }
}