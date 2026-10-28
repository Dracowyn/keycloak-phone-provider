package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * 腾讯云验证码资源提供者工厂
 * 负责创建TencentCaptchaResourceProvider实例
 */
public class TencentCaptchaResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "tencent";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new TencentCaptchaResourceProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // 无需初始化操作
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // 无需后初始化操作
    }

    @Override
    public void close() {
        // 无需清理资源
    }
}
