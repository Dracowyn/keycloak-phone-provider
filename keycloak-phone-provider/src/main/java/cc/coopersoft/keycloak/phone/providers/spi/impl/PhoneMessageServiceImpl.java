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
import cc.coopersoft.keycloak.phone.utils.PhoneNumberMask;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import java.time.LocalDateTime;
import java.util.Map;

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
     * 发送占位键前缀，完整键为 {@code 前缀 + realmId:区号:号码:类型}。
     */
    private static final String SEND_RESERVATION_KEY_PREFIX = "phone-sms-send:";

    /**
     * 发送在途时的占位秒数。只需盖住一次发送调用的耗时；节点在发送中途宕机时，该号码最多被锁这么久。
     */
    private static final long SEND_RESERVATION_SECONDS = 60;

    /**
     * 发送成功后把占位缩短到的秒数，只用来撑过本次事务提交。提交之后冷却由数据库里的发送记录判断：
     * 验证码被成功消费、记录删除后即可重发，不能被占位继续挡住。
     */
    private static final long SEND_RESERVATION_AFTER_SUCCESS_SECONDS = 5;

    /**
     * Keycloak会话对象
     */
    private final KeycloakSession session;

    /**
     * 服务名称
     */
    private final String service;

    /**
     * 配置的服务名称（未找到对应实现时 service 为空串）
     */
    private final String configuredService;

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
        // 只使用显式配置的短信服务商；不回退到任意已部署实现，避免配置拼错时静默落到 dummy（只打日志不发短信）
        this.configuredService = config.getSenderService();
        this.service = session.listProviderIds(MessageSenderService.class)
                .stream().filter(s -> s.equalsIgnoreCase(configuredService))
                .findFirst().orElse("");
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
        // 判断是否滥用短信验证码服务
        if (getTokenCodeService().isAbusing(phoneNumber, type)) {
            throw new ForbiddenException("You requested the maximum number of messages the last hour");
        }

        // 判断是否可以重新发送短信验证码
        if (!getTokenCodeService().canResend(phoneNumber, type)) {
            return resendCooldownResult(phoneNumber, type);
        }

        if (service.isEmpty()) {
            logger.errorf("SMS sender service '%s' is not deployed; refusing to send %s code to number ending %s",
                    configuredService, type.getLabel(), PhoneNumberMask.tail(phoneNumber));
            return MessageSendResult.failure(MessageSendResult.ERROR_CODE_SENDER_SERVICE_UNAVAILABLE,
                    "SMS sender service is not available");
        }

        // 上面两项检查都是「先查库再写库」：并发请求会在任何一条记录提交前同时通过，冷却和小时上限都会被绕过。
        // 发送前按 realm + 号码 + 类型原子占位，占位失败与冷却中同样返回
        SingleUseObjectProvider singleUseObjects = session.singleUseObjects();
        String reservationKey = sendReservationKey(phoneNumber, type);
        if (!singleUseObjects.putIfAbsent(reservationKey, SEND_RESERVATION_SECONDS)) {
            logger.debugf("%s code to number ending %s is already being sent, treating as resend cooldown",
                    type.getLabel(), PhoneNumberMask.tail(phoneNumber));
            return resendCooldownResult(phoneNumber, type);
        }
        SendReservation reservation = new SendReservation(singleUseObjects, reservationKey);
        session.getTransactionManager().enlistAfterCompletion(reservation);

        MessageSendResult result;
        try {
            result = sendReserved(phoneNumber, type);
        } catch (Throwable e) {
            reservation.release();
            throw e;
        }
        if (result.ok()) {
            // 时序：putIfAbsent、remove 立即写入缓存；put 进 Infinispan 事务，在数据库提交之后才写入，
            // 事务回滚时丢弃。这里把 60 秒的占位缩短到几秒，覆盖到本次发送记录提交、对其他请求可见为止，
            // 之后冷却和小时上限都按数据库记录判断
            singleUseObjects.put(reservationKey, SEND_RESERVATION_AFTER_SUCCESS_SECONDS, Map.of());
        } else {
            // 没有发出短信：释放占位，否则一次失败会锁住整个占位期
            reservation.release();
        }
        return result;
    }

    /**
     * 发送占位的释放。登记为事务完成后回调：Keycloak 26.8.2 的 {@code DefaultKeycloakTransactionManager}
     * 在数据库提交成功后对其调用 {@code commit()}；数据库提交失败、请求出错或被标记为只回滚时调用 {@code rollback()}。
     * <p>
     * 回滚时成功路径上的 {@code put} 被丢弃，只剩 60 秒的占位，要在这里删掉，否则发送成功、落库失败后该号码被白锁一分钟。
     * 已经在请求中途释放过的不再删，避免删掉其他请求随后放下的占位。
     */
    private static final class SendReservation extends AbstractKeycloakTransaction {

        private final SingleUseObjectProvider singleUseObjects;
        private final String key;
        private boolean released;

        private SendReservation(SingleUseObjectProvider singleUseObjects, String key) {
            this.singleUseObjects = singleUseObjects;
            this.key = key;
        }

        /**
         * 立即释放占位（没有发出短信时）。
         */
        void release() {
            if (!released) {
                released = true;
                singleUseObjects.remove(key);
            }
        }

        @Override
        protected void commitImpl() {
            // 提交成功：占位由成功路径上的 put 缩短，到期自然消失
        }

        @Override
        protected void rollbackImpl() {
            release();
        }
    }

    /**
     * 构造「冷却中」结果，能查到冷却截止时间时一并带上。
     */
    private MessageSendResult resendCooldownResult(PhoneNumber phoneNumber, TokenCodeType type) {
        MessageSendResult result = MessageSendResult.resendCooldown(MessageSendResult.ERROR_CODE_RATE_TIME,
                "Please wait for minutes.");
        try {
            LocalDateTime resendExpires = getTokenCodeService().getResendExpires(phoneNumber, type);
            if (resendExpires != null) {
                result.setResendExpires(resendExpires);
            }
        } catch (BadRequestException e) {
            // 冷却恰好在两次查询之间结束，或占位方的记录尚未提交，按无冷却时间返回即可
            logger.debug("Resend cooldown ended concurrently", e);
        }
        return result;
    }

    private String sendReservationKey(PhoneNumber phoneNumber, TokenCodeType type) {
        return SEND_RESERVATION_KEY_PREFIX + session.getContext().getRealm().getId() + ":"
                + phoneNumber.getAreaCode() + ":" + phoneNumber.getPhoneNumber() + ":" + type.name();
    }

    /**
     * 已占位后的实际发送：归属地检测、调用短信服务商、成功时落库，并记录事件。
     */
    private MessageSendResult sendReserved(PhoneNumber phoneNumber, TokenCodeType type) {
        MessageSendResult result;

        // 注意：发送前不删除旧验证码记录——最近1小时的记录要留给 isAbusing 做频控计数
        // （此前这里的 removeCode 导致表中至多1行，一小时防滥用上限永远不会触发）。
        // 旧记录由 persistCode 清理1小时前的部分；currentProcess 按 createdAt 取最新，旧验证码不会再被接受。

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

        TokenCodeRepresentation token = TokenCodeRepresentation.forPhoneNumber(phoneNumber);
        PhoneLocation phoneLocation = new PhoneLocation();
        // 判断是否启用归属地检测，并进行验证
        if (phoneLocation.verification(locationEnable, locationAppcode, phoneNumber, locationBlackList, session)) {
            logger.warnf("Illegal phone number ending %s", PhoneNumberMask.tail(phoneNumber));
            result = MessageSendResult.failure(MessageSendResult.ERROR_CODE_ILLEGAL_PHONE_NUMBER,
                    "Illegal phone number");

            // 记录号码归属地验证失败事件
            eventBuilder.detail("verification_result", "location_blocked")
                    .detail("error_code", MessageSendResult.ERROR_CODE_ILLEGAL_PHONE_NUMBER)
                    .detail("error_message", "Illegal phone number")
                    .error("SMS_LOCATION_BLOCKED");
        } else {
            try {
                // 发送短信验证码
                result = session.getProvider(MessageSenderService.class, service)
                        .sendSmsMessage(type, phoneNumber, token.getCode(), tokenExpiresIn);
            } catch (MessageSendException e) {
                result = MessageSendResult.failure(e.getErrorCode(), e.getErrorMessage());
            }
        }

        if (result.ok()) {
            getTokenCodeService().persistCode(token, type, result);
            logger.infof("Send %s SMS verification code to number ending %s over %s", type.getLabel(),
                    PhoneNumberMask.tail(phoneNumber), service);

            // 记录短信发送成功事件（不记录验证码明文，避免敏感信息落入事件日志）
            eventBuilder.detail("send_result", "success")
                    .detail("expires_time", String.valueOf(result.getExpiresTime()))
                    .detail("resend_expires_time", String.valueOf(result.getResendExpiresTime()))
                    .success();
        } else {
            logger.errorf("Message sending to number ending %s failed with %s: %s",
                    PhoneNumberMask.tail(phoneNumber), result.getErrorCode(), result.getErrorMessage());

            // 记录短信发送失败事件
            eventBuilder.detail("send_result", "failure")
                    .detail("error_code", result.getErrorCode())
                    .detail("error_message", result.getErrorMessage())
                    .error("SMS_SEND_FAILED");
        }
        return result;
    }
}