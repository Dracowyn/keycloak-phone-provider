package cc.coopersoft.keycloak.phone.providers.captcha.recaptcha;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import lombok.Setter;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.util.JsonSerialization;

import jakarta.ws.rs.core.MultivaluedMap;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class GoogleRecaptchaServiceImpl implements CaptchaService {
    public static final String G_RECAPTCHA_RESPONSE = "g-recaptcha-response";

    private static final Logger log = Logger.getLogger(GoogleRecaptchaServiceImpl.class);

    /**
     * 必填配置键：没有 secret 无法向 reCAPTCHA 校验，此时人机验证一律不通过。
     */
    private static final List<String> REQUIRED_CONFIG_KEYS = List.of("secret");

    private final KeycloakSession session;
    @Setter
    private Config.Scope config;

    public GoogleRecaptchaServiceImpl(KeycloakSession session) {
        this.session = session;
    }

    private String getUserIdByAuthResult(AuthenticationManager.AuthResult user) {

        String uid = "guest";
        if (user != null) {
            uid = user.user().getId();
        }
        return uid;
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
        return this.verify(formParams, this.getUserIdByAuthResult(user));
    }

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, String user) {
        // 没有 secret 时不再向 siteverify 发起注定失败的请求，直接按不通过处理
        List<String> missingKeys = missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("reCAPTCHA 配置不完整，缺少配置键 %s，人机验证不通过", missingKeys);
            return false;
        }

        boolean success = false;
        CloseableHttpClient httpClient = session.getProvider(HttpClientProvider.class).getHttpClient();
        HttpPost post = new HttpPost("https://www.recaptcha.net/recaptcha/api/siteverify");
        List<NameValuePair> listFormParams = new LinkedList<>();
        listFormParams.add(new BasicNameValuePair("secret", config.get("secret")));
        listFormParams.add(new BasicNameValuePair("response", formParams.getFirst(G_RECAPTCHA_RESPONSE)));
        listFormParams.add(new BasicNameValuePair("remoteip", session.getContext().getConnection().getRemoteAddr()));
        try {
            UrlEncodedFormEntity form = new UrlEncodedFormEntity(listFormParams, "UTF-8");
            post.setEntity(form);
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                InputStream content = response.getEntity().getContent();
                try {
                    Map json = JsonSerialization.readValue(content, Map.class);
                    Object val = json.get("success");
                    success = Boolean.TRUE.equals(val);
                } finally {
                    EntityUtils.consumeQuietly(response.getEntity());
                }
            }
        } catch (Exception e) {
            ServicesLogger.LOGGER.recaptchaFailed(e);
        }
        return success;
    }

    @Override
    public String getFrontendKey(AuthenticationManager.AuthResult user) {
        return "";
    }

    @Override
    public String getFrontendKey(String user) {
        return "";
    }

    @Override
    public void close() {
    }
}
