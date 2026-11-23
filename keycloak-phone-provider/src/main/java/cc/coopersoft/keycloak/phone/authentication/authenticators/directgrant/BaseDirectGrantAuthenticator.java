package cc.coopersoft.keycloak.phone.authentication.authenticators.directgrant;

import cc.coopersoft.keycloak.phone.providers.constants.ErrorCode;
import cc.coopersoft.keycloak.phone.providers.rest.dto.ApiError;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.PhoneNumberInput;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.OAuth2ErrorRepresentation;

/**
 * Direct Grant认证器基类
 * 提供统一的错误响应处理
 *
 * @author cooper
 * @since 2020/10/30
 */
public abstract class BaseDirectGrantAuthenticator implements Authenticator {

    /**
     * 构建错误响应（OAuth2格式，兼容旧版）
     *
     * @param status           HTTP状态码
     * @param error            错误代码
     * @param errorDescription 错误描述
     * @return 响应
     */
    public Response errorResponse(int status, String error, String errorDescription) {
        OAuth2ErrorRepresentation errorRep = new OAuth2ErrorRepresentation(error, errorDescription);
        return Response.status(status).entity(errorRep).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    /**
     * 构建错误响应（新格式）
     *
     * @param errorCode 错误码枚举
     * @return 响应
     */
    protected Response errorResponse(ErrorCode errorCode) {
        ApiError error = ApiError.from(errorCode);
        return Response.status(errorCode.getHttpStatus())
                .entity(error)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .build();
    }

    /**
     * 构建错误响应（新格式，带自定义消息）
     *
     * @param errorCode 错误码枚举
     * @param message   自定义消息
     * @return 响应
     */
    protected Response errorResponse(ErrorCode errorCode, String message) {
        ApiError error = ApiError.from(errorCode, message);
        return Response.status(errorCode.getHttpStatus())
                .entity(error)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .build();
    }

    /**
     * 请求里的号码，已校验并规范化。缺失或格式不合格时返回空号码，调用方按原有的 {@code isEmpty()} 分支
     * 返回 {@code invalid_grant}，与缺失时的响应一致。
     */
    protected PhoneNumber getPhoneNumber(AuthenticationFlowContext context){
        return PhoneNumberInput.fromFormOrEmpty(context.getHttpRequest().getDecodedFormParameters());
    }

    protected String getAuthenticationCode(AuthenticationFlowContext context){
        return context.getHttpRequest().getDecodedFormParameters().getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
    }

    protected void invalidCredentials(AuthenticationFlowContext context,AuthenticationFlowError error){
        context.getEvent().error(Errors.INVALID_USER_CREDENTIALS);
        Response challenge = errorResponse(Response.Status.UNAUTHORIZED.getStatusCode(), "invalid_grant", "Invalid user credentials");
        context.failure(error, challenge);
    }

    protected void invalidCredentials(AuthenticationFlowContext context, UserModel user){
        context.getEvent().user(user);
        invalidCredentials(context,AuthenticationFlowError.INVALID_CREDENTIALS);
    }

    protected void invalidCredentials(AuthenticationFlowContext context){
        invalidCredentials(context,AuthenticationFlowError.INVALID_USER);
    }

    @Override
    public void close() {}

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        authenticate(context);
    }
}
