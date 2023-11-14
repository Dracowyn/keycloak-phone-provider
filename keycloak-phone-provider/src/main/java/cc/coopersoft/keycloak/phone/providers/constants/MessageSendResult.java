package cc.coopersoft.keycloak.phone.providers.constants;

import lombok.Getter;

import java.time.Instant;
import java.util.Date;

@Getter
public class MessageSendResult {
    /**
     * 短信发送结果的状态
     */
    private final int status;
    /**
     * 错误代码
     */
    private String errorCode;
    /**
     * 错误信息
     */
    private String errorMessage;
    /**
     * 是否可以重发短信的截止时间
     */
    private Date resendExpires;
    /**
     * 短信的过期时间
     */
    private Date expires;

    /**
     * 构造方法
     *
     * @param status 结果状态
     */
    public MessageSendResult(int status) {
        this.status = status;
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
    public MessageSendResult setResendExpires(Date resendExpires) {
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
        Instant now = Instant.now();
        this.resendExpires = Date.from(now.plusSeconds(resendExpires));
        return this;
    }

    /**
     * 获取是否可以重发短信的截止时间的时间戳
     *
     * @return 是否可以重发短信的截止时间的时间戳
     */
    public long getResendExpiresTime() {
        return this.resendExpires != null ? this.resendExpires.getTime() : 0;
    }

    /**
     * 设置短信的过期时间
     *
     * @param expires 短信的过期时间
     * @return 当前MessageSendResult对象
     */
    public MessageSendResult setExpires(Date expires) {
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
        Instant now = Instant.now();
        this.expires = Date.from(now.plusSeconds(expires));
        return this;
    }

    /**
     * 获取短信的过期时间的时间戳
     *
     * @return 短信的过期时间的时间戳
     */
    public long getExpiresTime() {
        return this.expires != null ? this.expires.getTime() : 0;
    }
}
