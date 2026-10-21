package cc.coopersoft.keycloak.phone.providers.rest;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.rest.dto.SmsConfigResponse;
import cc.coopersoft.keycloak.phone.providers.rest.util.ResponseBuilder;
import cc.coopersoft.keycloak.phone.providers.spi.AreaCodeService;
import cc.coopersoft.keycloak.phone.providers.spi.ConfigService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import java.io.IOException;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * 短信服务资源
 * 提供短信配置查询和验证码相关接口
 *
 * @author cooper
 * @since 2020/10/30
 */
public class SmsResource {

    private static final Logger logger = Logger.getLogger(SmsResource.class);

    private final KeycloakSession session;

    public SmsResource(KeycloakSession session) {
        this.session = session;
    }

    /**
     * 获取短信配置
     *
     * @return 短信配置信息
     */
    @GET
    @Path("")
    @Produces(APPLICATION_JSON)
    public Response getSmsConfig() {
        try {
            ConfigService config = session.getProvider(ConfigService.class);
            AreaCodeService areaCodeService = session.getProvider(AreaCodeService.class);
            List<AreaCodeService.AreaCodeData> areaCodeList = areaCodeService.getAreaCodeList();

            SmsConfigResponse response = SmsConfigResponse.builder()
                    .tokenExpires(config.getTokenExpires())
                    .defaultAreaCode(String.valueOf(config.getDefaultAreaCode()))
                    .areaLocked(config.isAreaLocked())
                    .allowUnset(config.isAllowUnset())
                    .areaCodeList(areaCodeList)
                    .build();

            return ResponseBuilder.ok(response);
        } catch (IOException e) {
            logger.error("获取短信配置失败", e);
            return ResponseBuilder.serverError("获取短信配置失败");
        }
    }

    @Path("verification-code")
    public TokenCodeResource getVerificationCodeResource() {
        return new TokenCodeResource(session, TokenCodeType.VERIFY);
    }

    @Path("authentication-code")
    public TokenCodeResource getAuthenticationCodeResource() {
        return new TokenCodeResource(session, TokenCodeType.OTP);
    }

    @Path("login-code")
    public TokenCodeResource getLoginCodeResource() {
        return new TokenCodeResource(session, TokenCodeType.LOGIN);
    }

    @Path("registration-code")
    public TokenCodeResource getRegistrationCodeResource() {
        return new TokenCodeResource(session, TokenCodeType.REGISTRATION);
    }

    @Path("reset-code")
    public TokenCodeResource getResetCodeResource() {
        return new TokenCodeResource(session, TokenCodeType.RESET);
    }

    @Path("update-profile")
    public VerificationCodeResource getVerificateCodeResource() {
        if (!session.getProvider(ConfigService.class).isProfileUpdateEnabled()) {
            throw new NotFoundException();
        }
        return new VerificationCodeResource(session);
    }
}
