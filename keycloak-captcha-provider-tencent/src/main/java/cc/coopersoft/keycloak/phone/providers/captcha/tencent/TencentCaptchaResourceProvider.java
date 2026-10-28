package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

/**
 * 腾讯云验证码资源提供者
 * 作为RealmResourceProvider的实现，为Keycloak提供REST资源
 */
public class TencentCaptchaResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public TencentCaptchaResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return new TencentCaptchaResource(session);
    }

    @Override
    public void close() {
        // 无需清理资源
    }
}
