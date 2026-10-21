package cc.coopersoft.keycloak.phone.providers.rest;

import cc.coopersoft.keycloak.phone.providers.spi.ConfigService;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import static org.junit.jupiter.api.Assertions.*;

class SmsResourceGateTest {
    private ConfigService config(Boolean enabled) {
        Config.Scope scope = (Config.Scope) Proxy.newProxyInstance(Config.Scope.class.getClassLoader(),
                new Class<?>[] {Config.Scope.class}, (proxy, method, args) -> {
                    if ("getBoolean".equals(method.getName()) && "profileUpdateEnabled".equals(args[0]) && enabled != null) {
                        return enabled;
                    }
                    return args.length > 1 ? args[1] : null;
                });
        return new ConfigService(scope);
    }
    @Test void absentSettingDisablesProfileUpdates() {
        ConfigService c = config(null);
        assertFalse(c.isProfileUpdateEnabled());
        // Direct HTTP route rejection is exercised in the disposable runtime fixture.
    }
    @Test void explicitFalseDisablesProfileUpdates() {
        assertFalse(config(false).isProfileUpdateEnabled());
    }
    @Test void enablingRequiresExplicitTrue() {
        assertTrue(config(true).isProfileUpdateEnabled());
    }
}
