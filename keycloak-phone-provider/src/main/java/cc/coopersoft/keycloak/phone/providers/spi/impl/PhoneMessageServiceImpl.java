package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.ConfigService;
import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderService;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneMessageService;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneLocation;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;

import jakarta.ws.rs.ForbiddenException;

/**
 * 短信验证码服务实现类
 * 用于发送短信验证码
 * 该类实现了{@link PhoneMessageService}接口
 *
 * @author cooper
 * @since 2020/10/29
 */
public class PhoneMessageServiceImpl implements PhoneMessageService {

    /**
     * 日志记录器
     */
    private static final Logger logger = Logger.getLogger(PhoneMessageServiceImpl.class);

    /**
     * Keycloak会话对象
     */
    private final KeycloakSession session;

    /**
     * 服务名称
     */
    private final String service;

    /**
     * 访问短信验证码过期时间
     */
    private final int tokenExpiresIn;

    /**
     * 是否启用号码归属地检测
     */
    public final boolean locationEnable;

    /**
     * 号码归属地Appcode
     */
    public final String locationAppcode;

    /**
     * 号码归属地黑名单
     */
    public final String locationBlackList;

    /**
     * PhoneMessageServiceImpl类的构造函数
     *
     * @param session Keycloak会话对象
     */
    public PhoneMessageServiceImpl(KeycloakSession session) {
        this.session = session;

        // 获取配置服务对象
        ConfigService config = session.getProvider(ConfigService.class);
        this.service = session.listProviderIds(MessageSenderService.class)
                .stream().filter(s -> s.equals(config.getSenderService()))
                .findFirst().orElse(
                        session.listProviderIds(MessageSenderService.class)
                                .stream().findFirst().orElse("")
                );
        this.tokenExpiresIn = config.getTokenExpires();
        this.locationEnable = config.isLocationEnable();
        this.locationAppcode = config.getLocationAppcode();
        this.locationBlackList = config.getLocationBlackList();
    }

    /**
     * 关闭实现接口的方法
     */
    @Override
    public void close() {
    }

    /**
     * 获取短信验证码服务对象
     *
     * @return 短信验证码服务对象
     */
    private TokenCodeService getTokenCodeService() {
        return session.getProvider(TokenCodeService.class);
    }

    /**
     * 发送短信验证码的方法
     *
     * @param phoneNumber 电话号码
     * @param type        短信验证码类型
     * @return 短信验证码发送结果
     */
    @Override
    public MessageSendResult sendTokenCode(PhoneNumber phoneNumber, TokenCodeType type) {
        TokenCodeRepresentation token = TokenCodeRepresentation.forPhoneNumber(phoneNumber);
        PhoneMessageQuota quota = new PhoneMessageQuota(session);
        java.time.LocalDateTime retryAt = quota.reserve(phoneNumber, type, token.getId());
        if (retryAt != null) {
            return new MessageSendResult(-2).setError("rateTime", "Please wait for minutes.")
                    .setResendExpires(retryAt);
        }
        MessageSendResult result;

        // 创建事件构建器来记录短信发送详细事件
        EventBuilder eventBuilder = new EventBuilder(session.getContext().getRealm(), session, session.getContext().getConnection())
                .event(EventType.CUSTOM_REQUIRED_ACTION)
                .detail("action", "SMS_TOKEN_SEND")
                .detail("phone_number", phoneNumber.getFullPhoneNumber())
                .detail("token_code_type", type.name())
                .detail("area_code", phoneNumber.getAreaCode())
                .detail("sms_service_provider", service)
                .detail("token_expires_in", String.valueOf(tokenExpiresIn));

        // 尝试根据手机号查找用户并记录用户信息
        UserModel user = UserUtils.findUserByPhone(session.users(), session.getContext().getRealm(), phoneNumber);
        if (user != null) {
            eventBuilder.user(user);
        }

        PhoneLocation phoneLocation = new PhoneLocation();
        // 判断是否启用归属地检测，并进行验证
        if (phoneLocation.verification(locationEnable, locationAppcode, phoneNumber, locationBlackList, session)) {
            logger.warn("Illegal phone number:" + phoneNumber.getFullPhoneNumber());
            result = new MessageSendResult(-1).setError("illegalPhoneNumber", "Illegal phone number");

            // 记录号码归属地验证失败事件
            eventBuilder.detail("verification_result", "location_blocked")
                    .detail("error_code", "illegalPhoneNumber")
                    .detail("error_message", "Illegal phone number")
                    .error("SMS_LOCATION_BLOCKED");
        } else {
            try {
                // 发送短信验证码
                result = session.getProvider(MessageSenderService.class, service)
                        .sendSmsMessage(type, phoneNumber, token.getCode(), tokenExpiresIn);
            } catch (MessageSendException e) {
                result = new MessageSendResult(-1).setError(e.getErrorCode(), e.getErrorMessage());
            }
        }

        // This short transaction finishes before persistCode acquires the request lock.
        // Errors/rollback after this point keep the durable quota consumed.
        if (result.ok()) {
            quota.completed(token.getId(), result.getResendExpires());
            getTokenCodeService().persistCode(token, type, result);
            logger.info(String.format("Send %s SMS verification code to %s over %s", type.getLabel(), phoneNumber.getFullPhoneNumber(),
                    service));

            // 记录短信发送成功事件（不记录验证码明文，避免敏感信息落入事件日志）
            eventBuilder.detail("send_result", "success")
                    .detail("expires_time", String.valueOf(result.getExpiresTime()))
                    .detail("resend_expires_time", String.valueOf(result.getResendExpiresTime()))
                    .success();
        } else {
            logger.error(String.format("Message sending to %s failed with %s: %s",
                    phoneNumber.getFullPhoneNumber(), result.getErrorCode(), result.getErrorMessage()));

            // 记录短信发送失败事件
            eventBuilder.detail("send_result", "failure")
                    .detail("error_code", result.getErrorCode())
                    .detail("error_message", result.getErrorMessage())
                    .error("SMS_SEND_FAILED");
        }
        return result;
    }
}