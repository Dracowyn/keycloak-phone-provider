package cc.coopersoft.keycloak.phone.providers.captcha.geetest;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaServiceProviderFactory;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.List;

public class GeetestCaptchaServiceProviderFactory implements CaptchaServiceProviderFactory {
    private static final Logger log = Logger.getLogger(GeetestCaptchaServiceProviderFactory.class);

    private Config.Scope config;

    @Override
    public CaptchaService create(KeycloakSession session) {
        GeetestCaptchaServiceImpl geetestCaptchaServiceImpl = new GeetestCaptchaServiceImpl(session);
        geetestCaptchaServiceImpl.setConfig(this.config);
        return geetestCaptchaServiceImpl;
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
        // 启动时提示一次配置缺失；不抛异常、不阻止启动，运行时 verify 会一律拒绝
        List<String> missingKeys = GeetestCaptchaServiceImpl.missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("极验配置不完整，缺少配置键 %s；补齐前人机验证一律不通过", missingKeys);
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return "geetest";
    }
}
