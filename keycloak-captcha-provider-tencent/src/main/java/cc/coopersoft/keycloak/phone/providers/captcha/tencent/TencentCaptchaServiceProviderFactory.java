package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaServiceProviderFactory;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * 腾讯云验证码服务提供者工厂
 * 负责创建和配置TencentCaptchaServiceImpl实例
 */
public class TencentCaptchaServiceProviderFactory implements CaptchaServiceProviderFactory {
    private Config.Scope config;

    @Override
    public CaptchaService create(KeycloakSession session) {
        TencentCaptchaServiceImpl tencentCaptchaService = new TencentCaptchaServiceImpl(session);
        tencentCaptchaService.setConfig(this.config);
        return tencentCaptchaService;
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // 无需后初始化操作
    }

    @Override
    public void close() {
        // 无需清理资源
    }

    @Override
    public String getId() {
        return "tencent";
    }
}
