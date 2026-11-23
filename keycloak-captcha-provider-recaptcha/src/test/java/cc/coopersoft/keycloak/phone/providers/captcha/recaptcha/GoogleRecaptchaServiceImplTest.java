package cc.coopersoft.keycloak.phone.providers.captcha.recaptcha;

import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for reCAPTCHA required-config checks.
 */
class GoogleRecaptchaServiceImplTest {

    @Test
    @DisplayName("verify_missingSecret_rejectsWithoutCallingSiteverify")
    void verify_missingSecret_rejectsWithoutCallingSiteverify() {
        // 没有 secret：直接不通过，不再向 siteverify 发请求（session 为 null，若往下走会抛 NPE）
        GoogleRecaptchaServiceImpl service = new GoogleRecaptchaServiceImpl(null);
        service.setConfig(scope(Map.of()));
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle(GoogleRecaptchaServiceImpl.G_RECAPTCHA_RESPONSE, "token");

        assertThat(service.verify(form, "user")).isFalse();
        assertThat(GoogleRecaptchaServiceImpl.missingConfigKeys(scope(Map.of("secret", "")))).containsExactly("secret");
    }

    /** 只支持 {@code get(key)} 的配置桩，未配置的键返回 {@code null}。 */
    private static Config.Scope scope(Map<String, String> values) {
        return (Config.Scope) Proxy.newProxyInstance(Config.Scope.class.getClassLoader(),
                new Class<?>[]{Config.Scope.class}, (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && args.length == 1) {
                        return values.get((String) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
