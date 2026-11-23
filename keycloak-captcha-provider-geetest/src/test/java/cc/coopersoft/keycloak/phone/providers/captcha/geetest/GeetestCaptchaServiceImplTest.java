package cc.coopersoft.keycloak.phone.providers.captcha.geetest;

import com.geetest.sdk.GeetestLib;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for Geetest v3 offline-challenge bookkeeping, required-config checks and v4 failure handling.
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

    @Test
    @DisplayName("verifyV4_failures_passOnlyOnServiceFailureWithExplicitFallback")
    void verifyV4_failures_passOnlyOnServiceFailureWithExplicitFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/validate", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThat(v4Service(endpoint, "true").verify(v4Form(), "user"))
                    .as("HTTP 4xx 即使开启 fallback_on_error 也不放行").isFalse();
        } finally {
            server.stop(0);
        }

        // 桩服务已关闭，同一端口连接被拒，属于网络类异常
        assertThat(v4Service(endpoint, null).verify(v4Form(), "user")).as("默认不放行").isFalse();
        assertThat(v4Service(endpoint, "true").verify(v4Form(), "user")).as("显式开启 fallback_on_error 才放行").isTrue();
    }

    private static GeetestCaptchaServiceImpl v4Service(String endpoint, String fallbackOnError) {
        Map<String, String> values = new HashMap<>(Map.of("id", "gt-id", "key", "gt-key", "version", "4"));
        if (fallbackOnError != null) {
            values.put("fallback_on_error", fallbackOnError);
        }
        GeetestCaptchaServiceImpl service = new GeetestCaptchaServiceImpl(null);
        service.setConfig(scope(values));
        service.setV4ApiDomain(endpoint);
        return service;
    }

    private static MultivaluedHashMap<String, String> v4Form() {
        MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("captcha_output", "output");
        form.putSingle("gen_time", "1700000000");
        form.putSingle("lot_number", "lot");
        form.putSingle("pass_token", "token");
        return form;
    }

    /** 只支持 {@code get(key)} / {@code getBoolean(key)} 的配置桩，未配置的键返回 {@code null}。 */
    private static Config.Scope scope(Map<String, String> values) {
        return (Config.Scope) Proxy.newProxyInstance(Config.Scope.class.getClassLoader(),
                new Class<?>[]{Config.Scope.class}, (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && args.length == 1) {
                        return values.get((String) args[0]);
                    }
                    if ("getBoolean".equals(method.getName()) && args.length == 1) {
                        String value = values.get((String) args[0]);
                        return value == null ? null : Boolean.valueOf(value);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
