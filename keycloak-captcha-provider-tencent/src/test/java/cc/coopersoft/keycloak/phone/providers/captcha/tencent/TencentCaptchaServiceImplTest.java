package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for Tencent captcha required-config checks.
 */
class TencentCaptchaServiceImplTest {

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
