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
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import jakarta.ws.rs.ForbiddenException;

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
     * @param type 短信验证码类型
     * @return 短信验证码发送结果
     */
    @Override
    public MessageSendResult sendTokenCode(PhoneNumber phoneNumber, TokenCodeType type) {
        // 判断是否滥用短信验证码服务
        if (getTokenCodeService().isAbusing(phoneNumber, type)) {
            throw new ForbiddenException("You requested the maximum number of messages the last hour");
        }

        MessageSendResult result;

        // 判断是否可以重新发送短信验证码
        if (!getTokenCodeService().canResend(phoneNumber, type)) {
            TokenCodeRepresentation current = getTokenCodeService().currentProcess(phoneNumber, type);
            result = new MessageSendResult(-2).setError("RATE_LIMIT", "Please wait for minutes.");
            if (current != null && current.getResendExpiresAt() != null) {
                result.setResendExpires(current.getResendExpiresAt());
            }
            return result;
        }

        // 移除旧的短信验证码
        getTokenCodeService().removeCode(phoneNumber, type);

        TokenCodeRepresentation token = TokenCodeRepresentation.forPhoneNumber(phoneNumber);
//        logger.info(String.format("The code is %s", token.getCode()));
        PhoneLocation phoneLocation = new PhoneLocation();
        // 判断是否启用归属地检测，并进行验证
        if (locationEnable && phoneLocation.verification(locationAppcode, phoneNumber, locationBlackList)) {
            logger.warn("Illegal mobile phone number:" + phoneNumber.getFullPhoneNumber());
//            result = new MessageSendResult(0).setError("ILLEGAL_PHONE", "This mobile phone number is not supported yet.");
            throw new ForbiddenException("This mobile phone number is not supported yet.");
        } else {
            try {
                // 发送短信验证码
                result = session.getProvider(MessageSenderService.class, service)
                        .sendSmsMessage(type, phoneNumber, token.getCode(), tokenExpiresIn);
            } catch (MessageSendException e) {
                result = new MessageSendResult(-1).setError(e.getErrorCode(), e.getErrorMessage());
            }
        }

        // result = new MessageSendResult(1).setResendExpires(120).setExpires(tokenExpiresIn);
        if (result.ok()) {
            getTokenCodeService().persistCode(token, type, result);
            logger.info(String.format("Send %s SMS verification code: %s to %s over %s", type.getLabel(), token.getCode(), phoneNumber.getFullPhoneNumber(),
                    service));
        } else {
            logger.error(String.format("Message sending to %s failed with %s: %s",
                    phoneNumber.getFullPhoneNumber(), result.getErrorCode(), result.getErrorMessage()));
        }
        return result;
    }
}