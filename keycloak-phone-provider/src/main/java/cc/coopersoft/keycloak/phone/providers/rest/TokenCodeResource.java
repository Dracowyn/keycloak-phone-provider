package cc.coopersoft.keycloak.phone.providers.rest;

import cc.coopersoft.keycloak.phone.authentication.authenticators.directgrant.EverybodyPhoneAuthenticatorFactory;
import cc.coopersoft.keycloak.phone.providers.constants.ErrorCode;
import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.rest.dto.ApiError;
import cc.coopersoft.keycloak.phone.providers.rest.dto.ResendExpiresResponse;
import cc.coopersoft.keycloak.phone.providers.rest.dto.SmsCodeResponse;
import cc.coopersoft.keycloak.phone.providers.rest.util.ResponseBuilder;
import cc.coopersoft.keycloak.phone.providers.spi.AreaCodeService;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneMessageService;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.PhoneNumberInput;
import cc.coopersoft.keycloak.phone.utils.PhoneNumberMask;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * TokenCodeResource
 * 发送TokenCode的RESTful接口
 *
 * @author cooper
 * @since 2020/10/30
 */
public class TokenCodeResource {

    /**
     * 日志记录器
     */
    private static final Logger logger = Logger.getLogger(TokenCodeResource.class);

    /**
     * 「已部署人机验证实现但没有默认服务商」是部署配置错误，进程内只打一次 ERROR，之后降为 DEBUG，
     * 否则每个发送请求都会刷一条 ERROR。
     */
    private static final AtomicBoolean MISSING_DEFAULT_CAPTCHA_LOGGED = new AtomicBoolean();

    /**
     * Keycloak会话
     */
    protected final KeycloakSession session;

    /**
     * TokenCode类型
     */
    protected final TokenCodeType tokenCodeType;

    /**
     * 认证结果
     */
    private final AuthenticationManager.AuthResult auth;

    /**
     * TokenCodeResource构造函数
     *
     * @param session       Keycloak会话
     * @param tokenCodeType TokenCode类型
     */
    TokenCodeResource(KeycloakSession session, TokenCodeType tokenCodeType) {
        this.session = session;
        this.tokenCodeType = tokenCodeType;
        this.auth = new AppAuthManager().authenticateIdentityCookie(session, session.getContext().getRealm());
    }

    /**
     * 发送TokenCode的POST请求（JSON格式）
     *
     * @param reqBody 请求体
     * @return 响应
     */
    @POST
    @Path("")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public Response sendTokenCodeJson(String reqBody) {
        try {
            JsonNode jsonObject = new ObjectMapper().readTree(reqBody);
            MultivaluedHashMap<String, String> formData = new MultivaluedHashMap<>();
            for (Map.Entry<String, JsonNode> node : jsonObject.properties()) {
                formData.addAll(node.getKey(), node.getValue().asText());
            }
            return this.sendTokenCode(formData);
        } catch (IOException e) {
            logger.error("解析JSON请求体失败", e);
            return ResponseBuilder.error(ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * 发送TokenCode的POST请求（表单格式）
     *
     * @param formData 表单数据
     * @return 响应
     */
    @POST
    @Path("")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_FORM_URLENCODED)
    public Response sendTokenCode(MultivaluedMap<String, String> formData) {
        PhoneNumber rawPhoneNumber = new PhoneNumber(formData);

        // 验证手机号
        if (rawPhoneNumber.isEmpty()) {
            return ResponseBuilder.error(ErrorCode.PHONE_NUMBER_REQUIRED);
        }

        // 校验并规范化号码：后续频控、落库、调用短信服务商都用规范化结果，变体写法不能各算一个号码
        Optional<PhoneNumber> normalized = PhoneNumberInput.normalize(rawPhoneNumber);
        if (normalized.isEmpty()) {
            return ResponseBuilder.error(ErrorCode.PHONE_NUMBER_INVALID);
        }
        PhoneNumber phoneNumber = normalized.get();

        // 验证人机验证码
        if (!passesCaptcha(session, this.auth, formData)) {
            return ResponseBuilder.error(ErrorCode.CAPTCHA_REQUIRED);
        }

        // 验证区号
        AreaCodeService areaCodeService = session.getProvider(AreaCodeService.class);
        if (!areaCodeService.isAreaCodeAllowed(phoneNumber.getAreaCodeInt())) {
            return ResponseBuilder.error(ErrorCode.AREA_NOT_SUPPORTED);
        }

        // 检查用户是否存在；启用自动开户流程时允许新号码获取 OTP
        if (tokenCodeType != TokenCodeType.REGISTRATION && tokenCodeType != TokenCodeType.VERIFY) {
            UserModel user = UserUtils.findUserByPhone(session.users(), session.getContext().getRealm(), phoneNumber);
            if (user == null && !(tokenCodeType == TokenCodeType.OTP &&
                    isEverybodyPhoneAuthenticationEnabled(session.getContext().getRealm()))) {
                return ResponseBuilder.error(ErrorCode.USER_NOT_FOUND);
            }
        }

        logger.infof("请求发送 %s 验证码到尾号 %s", tokenCodeType.name(), PhoneNumberMask.tail(phoneNumber));

        // 创建事件构建器来记录短信发送事件
        EventBuilder eventBuilder = new EventBuilder(session.getContext().getRealm(), session, session.getContext().getConnection())
                .event(EventType.CUSTOM_REQUIRED_ACTION)
                .detail("phone_number", phoneNumber.getFullPhoneNumber())
                .detail("token_code_type", tokenCodeType.name())
                .detail("area_code", phoneNumber.getAreaCode());

        // 如果有已认证的用户，记录用户信息
        if (auth != null && auth.user() != null) {
            eventBuilder.user(auth.user());
        } else {
            // 对于注册和验证类型，尝试根据手机号查找用户
            UserModel user = UserUtils.findUserByPhone(session.users(), session.getContext().getRealm(), phoneNumber);
            if (user != null) {
                eventBuilder.user(user);
            }
        }

        // 发送短信验证码
        MessageSendResult result;
        try {
            result = session.getProvider(PhoneMessageService.class).sendTokenCode(phoneNumber, tokenCodeType);
        } catch (ForbiddenException e) {
            // 一小时内发送次数超过上限（防滥用）
            eventBuilder.detail("result", "failure")
                    .detail("error_code", "SMS_SEND_LIMIT_EXCEEDED")
                    .detail("error_message", e.getMessage())
                    .error("SMS_SEND_LIMIT_EXCEEDED");
            return ResponseBuilder.error(ErrorCode.SMS_SEND_LIMIT_EXCEEDED);
        }

        if (result.ok()) {
            // 记录成功事件
            eventBuilder.detail("result", "success")
                    .detail("expires_in", String.valueOf(result.getExpiresTime()))
                    .detail("resend_expires", String.valueOf(result.getResendExpiresTime()))
                    .success();

            // 返回成功响应
            SmsCodeResponse response = SmsCodeResponse.success(
                    result.getExpiresTime(),
                    result.getResendExpiresTime()
            );
            return ResponseBuilder.ok(response);
        } else {
            // 记录失败事件
            eventBuilder.detail("result", "failure")
                    .detail("error_code", result.getErrorCode())
                    .detail("error_message", result.getErrorMessage())
                    .error("SMS_SEND_FAILED");

            // 重发冷却中（errorCode: rateTime）
            if (result.isResendCooldown()) {
                if (result.getResendExpires() != null) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("resendExpires", result.getResendExpiresTime());
                    ApiError apiError = ApiError.from(ErrorCode.RESEND_TOO_SOON, details);
                    return ResponseBuilder.error(ErrorCode.RESEND_TOO_SOON.getHttpStatus(), apiError);
                }
                return ResponseBuilder.error(ErrorCode.RESEND_TOO_SOON);
            }

            // 手机号归属地不允许使用
            if (MessageSendResult.ERROR_CODE_ILLEGAL_PHONE_NUMBER.equals(result.getErrorCode())) {
                return ResponseBuilder.error(ErrorCode.ILLEGAL_PHONE_NUMBER);
            }

            // 其余失败，返回通用错误响应
            return ResponseBuilder.error(ErrorCode.SMS_SEND_FAILED, result.getErrorMessage());
        }
    }

    /**
     * 人机验证关卡。
     * <ul>
     *     <li>有默认 CaptchaService：先调用其 verify，不通过时再看是否受信客户端；</li>
     *     <li>已部署实现但没有默认服务商（多个实现并存且未指定）：拒绝，受信客户端除外；</li>
     *     <li>一个实现都没部署：跳过人机验证。</li>
     * </ul>
     *
     * @param session  Keycloak 会话
     * @param auth     当前登录身份，可为 {@code null}
     * @param formData 发送请求的参数
     * @return 是否放行
     */
    static boolean passesCaptcha(KeycloakSession session, AuthenticationManager.AuthResult auth,
                                 MultivaluedMap<String, String> formData) {
        String clientId = formData.getFirst("client_id");
        String clientSecret = formData.getFirst("client_secret");

        CaptchaService captchaService = session.getProvider(CaptchaService.class);
        if (captchaService != null) {
            return captchaService.verify(formData, auth) || isTrustedClient(session, clientId, clientSecret);
        }

        // provider 为 null 有两种情况：一个实现都没部署（有意不启用人机验证），
        // 或部署了多个实现却没指定默认服务商（Keycloak 无法选出默认实现）。后者是配置错误，不能静默放行。
        boolean captchaDeployed;
        try (Stream<ProviderFactory> factories = session.getKeycloakSessionFactory()
                .getProviderFactoriesStream(CaptchaService.class)) {
            captchaDeployed = factories.findAny().isPresent();
        }
        if (!captchaDeployed) {
            logger.warn("未部署任何CaptchaService实现，跳过人机验证");
            return true;
        }

        String message = "已部署CaptchaService实现但没有默认服务商，拒绝发送；多个实现并存时须用 "
                + "spi-captcha-service--provider=<provider-id> 指定其一（如 tencent、geetest、recaptcha）";
        if (MISSING_DEFAULT_CAPTCHA_LOGGED.compareAndSet(false, true)) {
            logger.error(message);
        } else {
            logger.debug(message);
        }
        return isTrustedClient(session, clientId, clientSecret);
    }

    static boolean isEverybodyPhoneAuthenticationEnabled(RealmModel realm) {
        try (Stream<AuthenticationFlowModel> flows = realm.getAuthenticationFlowsStream()) {
            return flows.filter(AuthenticationFlowModel::isTopLevel)
                    .anyMatch(flow -> hasEverybodyPhoneAuthenticator(realm, flow.getId()));
        }
    }

    private static boolean hasEverybodyPhoneAuthenticator(RealmModel realm, String flowId) {
        try (Stream<AuthenticationExecutionModel> executions = realm.getAuthenticationExecutionsStream(flowId)) {
            return executions.filter(AuthenticationExecutionModel::isEnabled)
                    .anyMatch(execution -> execution.isAuthenticatorFlow()
                            ? hasEverybodyPhoneAuthenticator(realm, execution.getFlowId())
                            : EverybodyPhoneAuthenticatorFactory.PROVIDER_ID.equals(execution.getAuthenticator()));
        }
    }

    /**
     * 获取Resend Expires的JSON响应
     *
     * @param reqBody 请求体
     * @return 响应
     */
    @POST
    @Path("/resend-expires")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    public Response getResendExpireJson(String reqBody) {
        try {
            JsonNode jsonObject = new ObjectMapper().readTree(reqBody);
            // 使用path(...).asText(null)安全取值，避免字段缺失时NPE
            String areaCode = jsonObject.path(PhoneConstants.FIELD_AREA_CODE).asText(null);
            String phoneNumberStr = jsonObject.path(PhoneConstants.FIELD_PHONE_NUMBER).asText(null);
            return this.getResendExpire(areaCode, phoneNumberStr);
        } catch (IOException e) {
            logger.error("解析JSON请求体失败", e);
            return ResponseBuilder.error(ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * 获取Resend Expires的POST请求
     *
     * @param areaCode    区号
     * @param phoneNumber 电话号码
     * @return 响应
     */
    @POST
    @Path("/resend-expires")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_FORM_URLENCODED)
    public Response getResendExpirePost(@FormParam(PhoneConstants.FIELD_AREA_CODE) String areaCode,
                                        @FormParam(PhoneConstants.FIELD_PHONE_NUMBER) String phoneNumber) {
        return this.getResendExpire(areaCode, phoneNumber);
    }

    /**
     * 获取Resend Expires的GET请求
     *
     * @param areaCode       区号
     * @param phoneNumberStr 电话号码
     * @return 响应
     */
    @GET
    @Path("/resend-expires")
    @Produces(APPLICATION_JSON)
    public Response getResendExpire(@QueryParam(PhoneConstants.FIELD_AREA_CODE) String areaCode,
                                    @QueryParam(PhoneConstants.FIELD_PHONE_NUMBER) String phoneNumberStr) {
        if (new PhoneNumber(areaCode, phoneNumberStr).isEmpty()) {
            return ResponseBuilder.error(ErrorCode.PHONE_NUMBER_REQUIRED);
        }
        Optional<PhoneNumber> normalized = PhoneNumberInput.normalize(areaCode, phoneNumberStr);
        if (normalized.isEmpty()) {
            return ResponseBuilder.error(ErrorCode.PHONE_NUMBER_INVALID);
        }
        PhoneNumber phoneNumber = normalized.get();

        TokenCodeService tokenCodeService = session.getProvider(TokenCodeService.class);
        try {
            LocalDateTime resendExpireDate = tokenCodeService.getResendExpires(phoneNumber, tokenCodeType);
            long resendExpire = resendExpireDate.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

            ResendExpiresResponse response = ResendExpiresResponse.of(resendExpire);
            return ResponseBuilder.ok(response);
        } catch (BadRequestException e) {
            // 没有冷却中的记录是正常业务分支（从未发送、冷却已过或验证码已被消费），不打 ERROR 和堆栈
            logger.debugf("尾号 %s 没有冷却中的 %s 验证码记录", PhoneNumberMask.tail(phoneNumber), tokenCodeType.name());
            return ResponseBuilder.error(ErrorCode.TOKEN_NOT_FOUND, e.getMessage());
        }
    }

    /**
     * 判断是否为受信任的客户端
     *
     * @param session Keycloak会话
     * @param id      客户端ID
     * @param secret  客户端 secret
     * @return true: 是信任的客户端; false: 不是信任的客户端
     */
    private static boolean isTrustedClient(KeycloakSession session, String id, String secret) {
        if (id == null || secret == null) {
            return false;
        }
        ClientModel client = session.getContext().getRealm().getClientByClientId(id);
        // 只有已启用的机密客户端可凭密钥跳过人机验证；公共客户端不应持有密钥，禁用客户端不应再被信任
        return client != null && client.isEnabled() && !client.isPublicClient() && client.validateSecret(secret);
    }
}
