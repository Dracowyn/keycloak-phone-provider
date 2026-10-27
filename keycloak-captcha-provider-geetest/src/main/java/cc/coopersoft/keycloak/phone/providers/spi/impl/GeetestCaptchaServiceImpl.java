package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geetest.sdk.GeetestLib;
import com.geetest.sdk.GeetestLibResult;
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

    // 极验3.0宕机模式按 challenge 登记：只有本服务在 register 失败时本地生成并登记过的 challenge，
    // 才允许走 failValidate（仅校验非空）。不能用进程级共享状态，否则一个用户初始化失败会让所有用户的校验被放行。
    private static final String OFFLINE_CHALLENGE_KEY_PREFIX = "geetest-v3-offline:";
    private static final long OFFLINE_CHALLENGE_LIFESPAN_SECONDS = 10 * 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeycloakSession session;
    @Setter
    private Config.Scope config;

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

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, AuthenticationManager.AuthResult user) {
        return verify(formParams, getUserIdByAuthResult(user));
    }

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, String user) {
        // 确保用户ID不为空
        user = Optional.ofNullable(user).orElse(UNKNOWN_USER);

        // 获取极验配置
        String geetestId = config.get("id");
        String geetestKey = config.get("key");
        if (geetestId == null || geetestKey == null) {
            log.warn("极验ID或密钥未配置，跳过验证");
            return true;
        }

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

        String url = String.format("%s/validate?captcha_id=%s", V4_API_DOMAIN, geetestId);

        try {
            // 发送API请求
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

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

            // 发送请求并获取响应
            String responseBody = sendRequest(conn, postData);
            log.debug("极验v4 API响应: " + responseBody);

            // 解析响应结果
            if (responseBody.contains("\"result\":\"success\"")) {
                return true;
            }

            log.warn("极验v4验证失败: " + responseBody);
            return false;

        } catch (Exception e) {
            // 处理连接错误 - 如果API不可用，根据配置决定是否通过验证
            log.warn("连接极验v4 API时出错: " + e.getMessage(), e);
            return Optional.ofNullable(config.getBoolean("fallback_on_error")).orElse(true);
        }
    }

    /**
     * 发送HTTP请求并获取响应
     *
     * @param conn     HTTP连接
     * @param postData 请求数据
     * @return 响应内容
     * @throws IOException 如果发生IO错误
     */
    private static String sendRequest(HttpURLConnection conn, StringBuilder postData) throws IOException {
        // 发送请求数据
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = postData.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 读取响应
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

        // 获取极验配置
        String geetestId = config.get("id");
        String geetestKey = config.get("key");

        if (geetestId == null || geetestKey == null) {
            log.error("必须配置极验ID和密钥");
            return "{\"success\":0,\"message\":\"极验ID或密钥未配置\"}";
        }

        // 获取极验版本，默认为3.0
        String geetestVersion = Optional.ofNullable(config.get("version")).orElse(DEFAULT_VERSION);

        // 如果是4.0版本，直接返回ID
        if ("4".equals(geetestVersion)) {
            return "{\"success\":1,\"gt\":\"" + geetestId + "\"}";
        }

        // 如果是3.0版本，返回验证结果
        GeetestLibResult result = getGeetestLibResult(user, geetestId, geetestKey);
        if (result.getStatus() != 1) {
            String challenge = extractChallenge(result.getData());
            if (challenge != null) {
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