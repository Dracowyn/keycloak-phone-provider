package cc.coopersoft.keycloak.phone.providers.rest;

import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialModel;
import cc.coopersoft.keycloak.phone.providers.constants.ErrorCode;
import cc.coopersoft.keycloak.phone.providers.rest.util.ResponseBuilder;
import cc.coopersoft.keycloak.phone.providers.spi.ConfigService;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.PhoneNumberInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * 验证码验证资源
 * 提供验证码验证和手机号绑定接口
 *
 * @author cooper
 * @since 2020/10/30
 */
public class VerificationCodeResource {

    private static final Logger logger = Logger.getLogger(VerificationCodeResource.class);

    private final KeycloakSession session;
    /**
     * 调用方校验结果，构造时算一次，三个写入口共用
     */
    private final WriteCaller caller;

    VerificationCodeResource(KeycloakSession session) {
        this.session = session;
        this.caller = WriteCaller.resolve(session);
    }

    /**
     * 号码写接口的调用方：先认 Bearer，再要求令牌的签发客户端（{@code azp}）在
     * {@code allowedWriteClients} 白名单内。两步都在解析请求体之前完成。
     * <p>
     * 不用 audience 或角色判断：Keycloak 26.8.2 实测，realm 内新建的普通公共客户端（默认 full scope）
     * 给用户签的令牌同样带 {@code aud=account} 和 {@code account} 的 {@code manage-account}，拦不住其他客户端。
     *
     * @param user      通过校验时的用户，未通过时为 {@code null}
     * @param rejection 未通过时的错误码，通过时为 {@code null}
     */
    private record WriteCaller(UserModel user, ErrorCode rejection) {

        static WriteCaller resolve(KeycloakSession session) {
            AuthResult auth = authenticateBearer(session);
            if (auth == null) {
                return new WriteCaller(null, ErrorCode.AUTHENTICATION_REQUIRED);
            }
            String azp = auth.token().getIssuedFor();
            Set<String> allowed = session.getProvider(ConfigService.class).getAllowedWriteClients();
            // 先判 null：不可变集合的 contains(null) 会抛 NPE
            if (azp == null || !allowed.contains(azp)) {
                // 外部请求可反复触发，只打 DEBUG
                logger.debugf("拒绝号码写请求：签发客户端 %s 不在白名单内，用户 %s", azp, auth.user().getId());
                return new WriteCaller(null, ErrorCode.CLIENT_NOT_ALLOWED);
            }
            return new WriteCaller(auth.user(), null);
        }

        boolean rejected() {
            return rejection != null;
        }
    }

    /**
     * 本资源只有写接口，一律只认 Bearer token，不认身份 Cookie，也不要回退到 Cookie：
     * {@code KEYCLOAK_IDENTITY} 是 {@code SameSite=None}，接受它会让任意站点用跨站表单 POST
     * 以受害者身份换绑号码或解绑号码（CSRF）。
     *
     * @param session Keycloak 会话
     * @return 认证结果；没有 Bearer、格式不对或令牌无效时返回 {@code null}
     */
    private static AuthResult authenticateBearer(KeycloakSession session) {
        try {
            return new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        } catch (NotAuthorizedException e) {
            // Authorization 头不是 Bearer 格式（如 Basic）：按未登录处理，返回统一错误格式而不是 Keycloak 默认的 401
            return null;
        }
    }

    private TokenCodeService getTokenCodeService() {
        return session.getProvider(TokenCodeService.class);
    }

    /**
     * 设置用户手机号（JSON格式）
     *
     * @param reqBody 请求体
     * @return 响应
     */
    @POST
    @Path("")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public Response setUserPhoneNumberJson(String reqBody) {
        // 先校验调用方再解析请求体：未登录（401）或客户端不在白名单（403）时不论请求体如何都直接拒绝
        if (caller.rejected()) {
            return ResponseBuilder.error(caller.rejection());
        }
        try {
            JsonNode jsonObject = new ObjectMapper().readTree(reqBody);

            // 使用path(...).asText(null)安全取值，避免字段缺失时NPE
            String areaCode = jsonObject.path(PhoneConstants.FIELD_AREA_CODE).asText(null);
            String phoneNumberStr = jsonObject.path(PhoneConstants.FIELD_PHONE_NUMBER).asText(null);
            String code = jsonObject.path(PhoneConstants.FIELD_VERIFICATION_CODE).asText(null);

            return this.setUserPhoneNumber(areaCode, phoneNumberStr, code);
        } catch (IOException e) {
            logger.error("解析JSON请求体失败", e);
            return ResponseBuilder.error(ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * 设置用户手机号（表单格式）
     *
     * @param areaCode     区号
     * @param phoneNumberStr 手机号
     * @param code         验证码
     * @return 响应
     */
    @POST
    @Path("")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_FORM_URLENCODED)
    public Response setUserPhoneNumber(@FormParam(PhoneConstants.FIELD_AREA_CODE) String areaCode,
                                       @FormParam(PhoneConstants.FIELD_PHONE_NUMBER) String phoneNumberStr,
                                       @FormParam(PhoneConstants.FIELD_VERIFICATION_CODE) String code) {

        // 验证用户是否登录、签发客户端是否在白名单内
        if (caller.rejected()) {
            return ResponseBuilder.error(caller.rejection());
        }

        // 验证手机号：缺失返回 PHONE_NUMBER_REQUIRED，格式不合格返回 PHONE_NUMBER_INVALID，之后只用规范化结果
        if (new PhoneNumber(areaCode, phoneNumberStr).isEmpty()) {
            return ResponseBuilder.error(ErrorCode.PHONE_NUMBER_REQUIRED);
        }
        Optional<PhoneNumber> normalized = PhoneNumberInput.normalize(areaCode, phoneNumberStr);
        if (normalized.isEmpty()) {
            return ResponseBuilder.error(ErrorCode.PHONE_NUMBER_INVALID);
        }
        PhoneNumber phoneNumber = normalized.get();

        // 验证验证码
        if (code == null || code.trim().isEmpty()) {
            return ResponseBuilder.error(ErrorCode.VERIFICATION_CODE_REQUIRED);
        }

        try {
            UserModel user = caller.user();
            getTokenCodeService().setUserPhoneNumberByCode(user, phoneNumber, code);
            return ResponseBuilder.noContent();
        } catch (ForbiddenException e) {
            // 验证码与预期值不匹配（常规客户端错误，warn级别即可）
            logger.warn("设置用户手机号失败：验证码不正确: " + e.getMessage());
            return ResponseBuilder.error(ErrorCode.VERIFICATION_CODE_INVALID, e.getMessage());
        } catch (BadRequestException e) {
            // 不存在有效的验证码流程（已过期或未发起）
            logger.warn("设置用户手机号失败：验证码已过期: " + e.getMessage());
            return ResponseBuilder.error(ErrorCode.VERIFICATION_CODE_EXPIRED, e.getMessage());
        }
    }

    /**
     * 取消绑定用户手机号
     *
     * @return 响应
     */
    @POST
    @Path("/unset")
    @Produces(APPLICATION_JSON)
    @Consumes({APPLICATION_JSON, APPLICATION_FORM_URLENCODED})
    public Response unsetUserPhoneNumber() {
        // 验证用户是否登录、签发客户端是否在白名单内
        if (caller.rejected()) {
            return ResponseBuilder.error(caller.rejection());
        }

        ConfigService config = session.getProvider(ConfigService.class);

        // 检查是否允许取消绑定
        if (!config.isAllowUnset()) {
            return ResponseBuilder.error(ErrorCode.PHONE_UNSET_NOT_ALLOWED);
        }

        UserModel user = caller.user();

        // 检查邮箱是否已验证
        if (!user.isEmailVerified()) {
            return ResponseBuilder.error(ErrorCode.EMAIL_NOT_VERIFIED, "取消绑定手机号前需要先验证邮箱");
        }

        // 移除手机号相关属性
        user.removeAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER);
        user.removeAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER_VERIFIED);

        // 删除该用户的手机号OTP凭据
        user.credentialManager()
                .getStoredCredentialsByTypeStream(PhoneOtpCredentialModel.TYPE)
                .map(CredentialModel::getId)
                .toList()
                .forEach(credentialId -> user.credentialManager().removeStoredCredentialById(credentialId));

        return ResponseBuilder.noContent();
    }
}