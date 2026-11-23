package cc.coopersoft.keycloak.phone.providers.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {

    private static final String KEY = "allowedWriteClients";

    @Test
    @DisplayName("未配置写接口客户端白名单时只允许 account-console")
    void allowedWriteClients_unset_defaultsToAccountConsole() {
        ConfigService config = new ConfigService(scope(Map.of()));

        assertThat(config.getAllowedWriteClients()).containsExactly("account-console");
    }

    @Test
    @DisplayName("白名单逐项去首尾空白、丢弃空项，结果不可变")
    void allowedWriteClients_configured_trimsAndDropsEmptyItems() {
        ConfigService config = new ConfigService(scope(Map.of(KEY, " my-account , ,mobile-app,, ")));

        assertThat(config.getAllowedWriteClients()).containsExactlyInAnyOrder("my-account", "mobile-app");
        assertThatThrownBy(() -> config.getAllowedWriteClients().add("evil"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("显式配置但解析后为空时白名单为空（拒绝所有写请求），不回退到默认值")
    void allowedWriteClients_onlySeparatorsOrBlank_failsClosed() {
        for (String raw : List.of(",", " , ,", " ", "")) {
            ConfigService config = new ConfigService(scope(Map.of(KEY, raw)));

            assertThat(config.getAllowedWriteClients()).as("配置值 [%s]", raw).isEmpty();
        }
    }

    /**
     * 只按键返回字符串配置的 {@link Config.Scope}；数值和布尔类配置一律返回调用方给的默认值。
     */
    private static Config.Scope scope(Map<String, String> values) {
        return (Config.Scope) Proxy.newProxyInstance(
                ConfigServiceTest.class.getClassLoader(),
                new Class<?>[]{Config.Scope.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> args.length == 1
                            ? values.get((String) args[0])
                            : values.getOrDefault((String) args[0], (String) args[1]);
                    case "getInt", "getLong", "getBoolean" -> args[1];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
