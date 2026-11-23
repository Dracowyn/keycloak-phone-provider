package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.common.ClientConnection;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for Tencent captcha required-config checks and disaster-ticket handling.
 */
class TencentCaptchaServiceImplTest {

    private static final Map<String, String> COMPLETE_CONFIG = Map.of(
            "secretId", "sid", "secretKey", "skey", "captchaAppId", "123", "appSecretKey", "akey");

    @Test
    @DisplayName("verify_missingConfig_rejectsInsteadOfSkipping")
    void verify_missingConfig_rejectsInsteadOfSkipping() {
        // 四个必填键缺 appSecretKey：以前会跳过验证直接放行，现在必须不通过（且不再往下调用腾讯云）
        TencentCaptchaServiceImpl service = new TencentCaptchaServiceImpl(null);
        service.setConfig(scope(Map.of("secretId", "sid", "secretKey", "skey", "captchaAppId", "123")));
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("ticket", "ticket");
        form.putSingle("randstr", "randstr");

        assertThat(service.verify(form, "user")).isFalse();
        assertThat(TencentCaptchaServiceImpl.missingConfigKeys(scope(Map.of("secretKey", "skey"))))
                .containsExactly("secretId", "captchaAppId", "appSecretKey");
    }

    @Test
    @DisplayName("容灾票据默认拒绝，只有显式配置 allowDisasterTicket=true 才放行")
    void verify_disasterTicket_rejectedUnlessExplicitlyAllowed() {
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("ticket", "trerror_1001_123_1700000000");
        form.putSingle("randstr", "x");

        TencentCaptchaServiceImpl byDefault = new TencentCaptchaServiceImpl(session());
        byDefault.setConfig(scope(COMPLETE_CONFIG));
        assertThat(byDefault.verify(form, "user")).as("默认不放行伪造的容灾票据").isFalse();

        Map<String, String> allowed = new HashMap<>(COMPLETE_CONFIG);
        allowed.put("allowDisasterTicket", "true");
        TencentCaptchaServiceImpl optedIn = new TencentCaptchaServiceImpl(session());
        optedIn.setConfig(scope(allowed));
        assertThat(optedIn.verify(form, "user")).as("显式开启后放行").isTrue();
    }

    /** 只支持 {@code get(key)}、{@code getBoolean(key, default)} 的配置桩，未配置的键返回 {@code null} 或默认值。 */
    private static Config.Scope scope(Map<String, String> values) {
        return (Config.Scope) Proxy.newProxyInstance(Config.Scope.class.getClassLoader(),
                new Class<?>[]{Config.Scope.class}, (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && args.length == 1) {
                        return values.get((String) args[0]);
                    }
                    if ("getBoolean".equals(method.getName()) && args.length == 2) {
                        String value = values.get((String) args[0]);
                        return value == null ? args[1] : Boolean.valueOf(value);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 只支持读取客户端 IP 的会话桩。 */
    private static KeycloakSession session() {
        ClientConnection connection = (ClientConnection) Proxy.newProxyInstance(ClientConnection.class.getClassLoader(),
                new Class<?>[]{ClientConnection.class}, (proxy, method, args) -> {
                    if ("getRemoteAddr".equals(method.getName())) {
                        return "127.0.0.1";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        KeycloakContext context = (KeycloakContext) Proxy.newProxyInstance(KeycloakContext.class.getClassLoader(),
                new Class<?>[]{KeycloakContext.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return connection;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (KeycloakSession) Proxy.newProxyInstance(KeycloakSession.class.getClassLoader(),
                new Class<?>[]{KeycloakSession.class}, (proxy, method, args) -> {
                    if ("getContext".equals(method.getName())) {
                        return context;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
