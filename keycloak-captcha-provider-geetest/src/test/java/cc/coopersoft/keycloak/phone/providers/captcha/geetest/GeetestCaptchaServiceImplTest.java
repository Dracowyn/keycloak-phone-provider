package cc.coopersoft.keycloak.phone.providers.captcha.geetest;

import com.geetest.sdk.GeetestLib;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for Geetest v3 offline-challenge bookkeeping and required-config checks.
 */
class GeetestCaptchaServiceImplTest {

    @Test
    @DisplayName("extractChallenge_registerResult_returnsChallenge")
    void extractChallenge_registerResult_returnsChallenge() {
        String data = "{\"success\":0,\"gt\":\"gt-id\",\"challenge\":\"abc123\",\"new_captcha\":true}";
        assertThat(GeetestCaptchaServiceImpl.extractChallenge(data)).isEqualTo("abc123");
    }

    @Test
    @DisplayName("extractChallenge_missingOrEmptyChallenge_returnsNull")
    void extractChallenge_missingOrEmptyChallenge_returnsNull() {
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("{\"success\":0}")).isNull();
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("{\"challenge\":\"\"}")).isNull();
        assertThat(GeetestCaptchaServiceImpl.extractChallenge(null)).isNull();
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("")).isNull();
    }

    @Test
    @DisplayName("extractChallenge_malformedJson_returnsNull")
    void extractChallenge_malformedJson_returnsNull() {
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("not json")).isNull();
    }

    @Test
    @DisplayName("offlineChallengeKey_differentChallenges_produceDistinctKeys")
    void offlineChallengeKey_differentChallenges_produceDistinctKeys() {
        assertThat(GeetestCaptchaServiceImpl.offlineChallengeKey("a"))
                .isNotEqualTo(GeetestCaptchaServiceImpl.offlineChallengeKey("b"))
                .startsWith("geetest-v3-offline:");
    }

    @Test
    @DisplayName("verify_missingConfig_rejectsInsteadOfSkipping")
    void verify_missingConfig_rejectsInsteadOfSkipping() {
        // 只配了 id、缺 key：以前会跳过验证直接放行，现在必须不通过（且不再往下调用极验服务）
        GeetestCaptchaServiceImpl service = new GeetestCaptchaServiceImpl(null);
        service.setConfig(scope(Map.of("id", "gt-id")));
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle(GeetestLib.GEETEST_CHALLENGE, "challenge");
        form.putSingle(GeetestLib.GEETEST_VALIDATE, "validate");
        form.putSingle(GeetestLib.GEETEST_SECCODE, "seccode");

        assertThat(service.verify(form, "user")).isFalse();
        assertThat(GeetestCaptchaServiceImpl.missingConfigKeys(scope(Map.of("id", "gt-id", "key", " "))))
                .containsExactly("key");
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
