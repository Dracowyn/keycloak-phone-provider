package cc.coopersoft.keycloak.phone.providers.sender;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderService;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.RealmModel;

public class DummySmsSenderServiceProvider implements MessageSenderService {

    private static final Logger logger = Logger.getLogger(DummySmsSenderServiceProvider.class);

    public DummySmsSenderServiceProvider(Config.Scope config, RealmModel realm) {
    }


    @Override
    public MessageSendResult sendSmsMessage(TokenCodeType type, PhoneNumber phoneNumber, String code, int expires) {
        logger.info(String.format("To: %s >>> %s", phoneNumber, code));
        return new MessageSendResult(1).setResendExpires(60).setExpires(expires);
    }

    @Override
    public void close() {
    }
}
