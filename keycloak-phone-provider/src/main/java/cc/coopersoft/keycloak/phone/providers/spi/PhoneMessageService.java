package cc.coopersoft.keycloak.phone.providers.spi;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.keycloak.provider.Provider;


public interface PhoneMessageService extends Provider {

    //TODO on key longin support
    //boolean Verification(String phoneNumber, String token);

    /**
     * 发送验证码
     *
     * @param phoneNumber 手机号
     * @param type        验证码类型
     * @return 发送结果
     */
    MessageSendResult sendTokenCode(PhoneNumber phoneNumber, TokenCodeType type);
}
