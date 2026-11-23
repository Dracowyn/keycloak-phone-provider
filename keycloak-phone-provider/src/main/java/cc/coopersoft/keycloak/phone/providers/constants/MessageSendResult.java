package cc.coopersoft.keycloak.phone.providers.constants;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 短信发送结果类
 * 用于封装短信发送的结果信息
 *
 * @author cooper
 * @since 2020/10/29
 */
public class MessageSendResult {

    /**
     * 重发冷却中的错误码
     */
    public static final String ERROR_CODE_RATE_TIME = "rateTime";

    /**
     * 手机号归属地不允许使用的错误码
     */
    public static final String ERROR_CODE_ILLEGAL_PHONE_NUMBER = "illegalPhoneNumber";

    /**
     * 短信服务商未部署的错误码
     */
    public static final String ERROR_CODE_SENDER_SERVICE_UNAVAILABLE = "senderServiceUnavailable";

    /**
     * 发送结果状态
     */
    public enum Status {
        SUCCESS(1),
        FAILURE(-1),
        RESEND_COOLDOWN(-2);

        @Getter
        private final int code;

        Status(int code) {
            this.code = code;
        }

        /**
         * 把历史 int 状态映射到枚举：正数为成功，-2 为冷却中，其余为失败。
         *
         * @param code int 状态值
         * @return 对应状态
         */
        static Status fromCode(int code) {
            if (code > 0) {
                return SUCCESS;
            }
            return code == RESEND_COOLDOWN.code ? RESEND_COOLDOWN : FAILURE;
        }
    }

    /**
     * 短信发送结果的状态（对外仍暴露原始 int 值）
     */
    @Getter
    private final int status;

    /**
     * 短信发送结果的状态枚举
     */
    private final Status statusEnum;

    /**
     * 错误代码
     */
    @Getter
    private String errorCode;

    /**
     * 错误信息
     */
    @Getter
    private String errorMessage;

    /**
     * 是否可以重发短信的截止时间
     */
    @Getter
    private LocalDateTime resendExpires;

    /**
     * 短信的过期时间
     */
    @Getter
    private LocalDateTime expires;

    /**
     * 构造方法
     *
     * @param status 结果状态（>0表示成功，<=0表示失败）
     * @deprecated 使用 {@link #success()}、{@link #failure(String, String)} 或 {@link #resendCooldown(String, String)}
     */
    @Deprecated
    public MessageSendResult(int status) {
        this.status = status;
        this.statusEnum = Status.fromCode(status);
    }

    private MessageSendResult(Status status) {
        this.status = status.getCode();
        this.statusEnum = status;
    }

    /**
     * 创建成功结果
     *
     * @return MessageSendResult实例
     */
    public static MessageSendResult success() {
        return new MessageSendResult(Status.SUCCESS);
    }

    /**
     * 创建失败结果
     *
     * @param errorCode 错误代码
     * @param errorMessage 错误信息
     * @return MessageSendResult实例
     */
    public static MessageSendResult failure(String errorCode, String errorMessage) {
        return new MessageSendResult(Status.FAILURE).setError(errorCode, errorMessage);
    }

    /**
     * 创建失败结果（使用ErrorCode枚举）
     *
     * @param errorCode 错误码枚举
     * @return MessageSendResult实例
     */
    public static MessageSendResult failure(ErrorCode errorCode) {
        return new MessageSendResult(Status.FAILURE).setError(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 创建重发冷却结果
     *
     * @param errorCode 错误代码
     * @param errorMessage 错误信息
     * @return MessageSendResult实例
     */
    public static MessageSendResult resendCooldown(String errorCode, String errorMessage) {
        return new MessageSendResult(Status.RESEND_COOLDOWN).setError(errorCode, errorMessage);
    }

    /**
     * 判断结果是否正常
     *
     * @return 结果是否正常
     */
    public boolean ok() {
        return this.status > 0;
    }

    /**
     * 判断结果是否处于重发冷却中
     *
     * @return 是否处于重发冷却中
     */
    public boolean isResendCooldown() {
        return this.statusEnum == Status.RESEND_COOLDOWN;
    }

    /**
     * 设置错误代码和错误信息
     *
     * @param code    错误代码
     * @param message 错误信息
     * @return 当前MessageSendResult对象
     */
    public MessageSendResult setError(String code, String message) {
        this.errorCode = code;
        this.errorMessage = message;
        return this;
    }

    /**
     * 设置是否可以重发短信的截止时间
     *
     * @param resendExpires 重发短信的截止时间
     * @return 当前MessageSendResult对象
     */
    public MessageSendResult setResendExpires(LocalDateTime resendExpires) {
        this.resendExpires = resendExpires;
        return this;
    }

    /**
     * 设置是否可以重发短信的截止时间，以秒为单位
     *
     * @param resendExpires 重发短信的秒数
     * @return 当前MessageSendResult对象
     */
    public MessageSendResult setResendExpires(int resendExpires) {
        this.resendExpires = LocalDateTime.now().plusSeconds(resendExpires);
        return this;
    }

    /**
     * 获取是否可以重发短信的截止时间的时间戳
     *
     * @return 是否可以重发短信的截止时间的时间戳
     */
    public long getResendExpiresTime() {
        return this.resendExpires != null ?
            this.resendExpires.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 0;
    }

    /**
     * 设置短信的过期时间
     *
     * @param expires 短信的过期时间
     * @return 当前MessageSendResult对象
     */
    public MessageSendResult setExpires(LocalDateTime expires) {
        this.expires = expires;
        return this;
    }

    /**
     * 设置短信的过期时间，以秒为单位
     *
     * @param expires 短信的秒数
     * @return 当前MessageSendResult对象
     */
    public MessageSendResult setExpires(int expires) {
        this.expires = LocalDateTime.now().plusSeconds(expires);
        return this;
    }

    /**
     * 获取短信的过期时间的时间戳
     *
     * @return 短信的过期时间的时间戳
     */
    public long getExpiresTime() {
        return this.expires != null ?
            this.expires.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 0;
    }
}
