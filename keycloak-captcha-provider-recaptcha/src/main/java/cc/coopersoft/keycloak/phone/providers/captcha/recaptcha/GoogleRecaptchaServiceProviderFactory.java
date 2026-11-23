package cc.coopersoft.keycloak.phone.providers.captcha.recaptcha;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaServiceProviderFactory;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.List;

public class GoogleRecaptchaServiceProviderFactory implements CaptchaServiceProviderFactory {
    private static final Logger log = Logger.getLogger(GoogleRecaptchaServiceProviderFactory.class);

    private Config.Scope config;

    @Override
    public CaptchaService create(KeycloakSession session) {
        GoogleRecaptchaServiceImpl recaptchaService = new GoogleRecaptchaServiceImpl(session);
        recaptchaService.setConfig(config);
        return recaptchaService;
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
        // 启动时提示一次配置缺失；不抛异常、不阻止启动，运行时 verify 会一律拒绝
        List<String> missingKeys = GoogleRecaptchaServiceImpl.missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("reCAPTCHA 配置不完整，缺少配置键 %s；补齐前人机验证一律不通过", missingKeys);
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
        return "recaptcha";
    }
}
