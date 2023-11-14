package cc.coopersoft.keycloak.phone.providers.spi;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.keycloak.models.UserModel;
import org.keycloak.provider.Provider;

import java.util.Date;

/**
 * 验证码服务
 */
public interface TokenCodeService extends Provider {
    /**
     * 获取当前正在处理的验证码
     *
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     * @return 验证码
     */
    TokenCodeRepresentation currentProcess(PhoneNumber phoneNumber, TokenCodeType tokenCodeType);

    /**
     * 获取当前正在处理的验证码
     *
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     */
    void removeCode(PhoneNumber phoneNumber, TokenCodeType tokenCodeType);

    /**
     * 是否可以重新发送验证码
     *
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     * @return 是否可以重新发送验证码
     */
    boolean canResend(PhoneNumber phoneNumber, TokenCodeType tokenCodeType);

    /**
     * 是否可以重新发送验证码
     *
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     * @return 是否可以重新发送验证码
     */
    boolean isAbusing(PhoneNumber phoneNumber, TokenCodeType tokenCodeType);

    /**
     * 保存验证码
     *
     * @param tokenCode     验证码
     * @param tokenCodeType 验证码类型
     * @param sendResult    发送结果
     */
    void persistCode(TokenCodeRepresentation tokenCode, TokenCodeType tokenCodeType, MessageSendResult sendResult);

    /**
     * 验证验证码
     *
     * @param phoneNumber 手机号
     * @param code        验证码
     * @return 是否验证通过
     */
    boolean validateCode(PhoneNumber phoneNumber, String code);

    /**
     * 验证验证码
     *
     * @param phoneNumber   手机号
     * @param code          验证码
     * @param tokenCodeType 验证码类型
     * @return 是否验证通过
     */
    boolean validateCode(PhoneNumber phoneNumber, String code, TokenCodeType tokenCodeType);

    /**
     * 验证验证码
     *
     * @param user        用户
     * @param phoneNumber 手机号
     * @param code        验证码
     * @return 是否验证通过
     */
    boolean validateCode(UserModel user, PhoneNumber phoneNumber, String code);

    /**
     * 验证验证码
     *
     * @param user          用户
     * @param phoneNumber   手机号
     * @param code          验证码
     * @param tokenCodeType 验证码类型
     * @return 是否验证通过
     */
    boolean validateCode(UserModel user, PhoneNumber phoneNumber, String code, TokenCodeType tokenCodeType);

    /**
     * 设置用户手机号
     *
     * @param user        用户
     * @param phoneNumber 手机号
     * @param code        验证码
     */
    void setUserPhoneNumberByCode(UserModel user, PhoneNumber phoneNumber, String code);

    /**
     * 清理验证码
     *
     * @param user 用户
     */
    void cleanUpAction(UserModel user);

    /**
     * 验证码验证通过
     *
     * @param user        用户
     * @param phoneNumber 手机号
     * @param tokenCodeId 验证码ID
     */
    void tokenValidated(UserModel user, PhoneNumber phoneNumber, String tokenCodeId);

    /**
     * 获取验证码过期时间
     *
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     * @return 验证码过期时间
     */
    Date getResendExpires(PhoneNumber phoneNumber, TokenCodeType tokenCodeType);
}
