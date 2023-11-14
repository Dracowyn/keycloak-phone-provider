package cc.coopersoft.keycloak.phone.providers.constants;

import lombok.Getter;

/**
 * TokenCode类型枚举
 * @author hyperquantum
 */
@Getter
public enum TokenCodeType {
    /**
     * 验证码
     */
    VERIFY("verification"),
    /**
     * 令牌
     */
    OTP("authentication"),
    /**
     * 重置凭证
     */
    RESET("reset credential"),
    /**
     * 注册
     */
    REGISTRATION("registration"),
    /**
     * 登录
     */
    LOGIN("login");

    private final String label;

    TokenCodeType(String label) {
        this.label  = label;
    }
}
