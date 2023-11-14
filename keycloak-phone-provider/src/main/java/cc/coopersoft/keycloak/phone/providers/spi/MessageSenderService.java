package cc.coopersoft.keycloak.phone.providers.spi;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.keycloak.provider.Provider;


/**
 * SMS, Voice, APP
 */
public interface MessageSenderService extends Provider {

    //void sendVoiceMessage(TokenCodeType type, String realmName, String realmDisplayName, String phoneNumber, String code , int expires) throws MessageSendException;

    /**
     * 短信发送接口
     *
     * @param type        验证码类型
     * @param phoneNumber 手机号
     * @param code        验证码
     * @param expires     有效期
     * @return 发送结果
     * @throws MessageSendException 发送异常
     */
    MessageSendResult sendSmsMessage(TokenCodeType type, PhoneNumber phoneNumber, String code, int expires) throws MessageSendException;
}
