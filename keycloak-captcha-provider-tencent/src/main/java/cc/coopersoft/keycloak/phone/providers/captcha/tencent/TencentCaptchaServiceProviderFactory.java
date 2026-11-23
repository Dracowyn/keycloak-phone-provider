package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaServiceProviderFactory;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.List;

/**
 * 腾讯云验证码服务提供者工厂
 * 负责创建和配置TencentCaptchaServiceImpl实例
 */
public class TencentCaptchaServiceProviderFactory implements CaptchaServiceProviderFactory {
    private static final Logger log = Logger.getLogger(TencentCaptchaServiceProviderFactory.class);

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
        // 启动时提示一次配置缺失；不抛异常、不阻止启动，运行时 verify 会一律拒绝
        List<String> missingKeys = TencentCaptchaServiceImpl.missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("腾讯云验证码配置不完整，缺少配置键 %s；补齐前人机验证一律不通过", missingKeys);
        }
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
