package cc.coopersoft.keycloak.phone.providers.constants;

import lombok.Getter;

/**
 * API错误码枚举
 * 定义所有标准错误码及其对应的HTTP状态码和消息
 *
 * @author Dracowyn
 * @since 2026-01-15
 */
@Getter
public enum ErrorCode {
    // 客户端错误 (4xx)
    PHONE_NUMBER_REQUIRED(400, "手机号码不能为空"),
    PHONE_NUMBER_INVALID(400, "手机号码格式不正确"),
    VERIFICATION_CODE_REQUIRED(400, "验证码不能为空"),
    VERIFICATION_CODE_INVALID(400, "验证码不正确"),
    VERIFICATION_CODE_EXPIRED(400, "验证码已过期"),
    CAPTCHA_REQUIRED(400, "人机验证未完成"),
    CAPTCHA_INVALID(400, "人机验证失败"),
    INVALID_REQUEST(400, "请求格式错误"),
    AREA_NOT_SUPPORTED(403, "该地区暂不支持"),
    USER_NOT_FOUND(404, "用户不存在"),
    AUTHENTICATION_REQUIRED(401, "需要登录"),
    CLIENT_NOT_ALLOWED(403, "当前应用无权修改手机号"),
    EMAIL_NOT_VERIFIED(400, "邮箱未验证"),
    PHONE_UNSET_NOT_ALLOWED(403, "不允许取消绑定手机号"),
    TOKEN_NOT_FOUND(404, "验证码不存在或已失效"),
    RESEND_TOO_SOON(429, "请求过于频繁，请稍后再试"),
    SMS_SEND_LIMIT_EXCEEDED(429, "短信请求过于频繁，请稍后再试"),
    
    // 服务器错误 (5xx)
    SMS_SEND_FAILED(500, "短信发送失败"),
    LOCATION_CHECK_FAILED(500, "归属地检测失败"),
    ILLEGAL_PHONE_NUMBER(403, "该手机号归属地不允许使用"),
    INTERNAL_ERROR(500, "服务器内部错误");

    /**
     * HTTP状态码
     */
    private final int httpStatus;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 构造函数
     *
     * @param httpStatus HTTP状态码
     * @param message    错误消息
     */
    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /**
     * 获取错误码名称
     *
     * @return 错误码名称
     */
    public String getCode() {
        return this.name();
    }
}
