package cc.coopersoft.keycloak.phone.providers.spi;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;

public abstract class AbstractFullSmsSenderService implements MessageSenderService {

    private final String realmDisplay;

    public AbstractFullSmsSenderService(String realmDisplay) {
        this.realmDisplay = realmDisplay;
    }

    /**
     * 发送短信
     *
     * @param phoneNumber 手机号
     * @param message     短信内容
     * @return 发送结果
     * @throws MessageSendException 发送异常
     */
    public abstract MessageSendResult sendMessage(String phoneNumber, String message) throws MessageSendException;


    public MessageSendResult sendSmsMessage(TokenCodeType type, String phoneNumber, String code, int expires) throws MessageSendException {
        final String message = String.format("[%s] - " + type.getLabel() + " code: %s, expires: %s minute ", realmDisplay, code, expires / 60);
        return sendMessage(phoneNumber, message);
    }
}
