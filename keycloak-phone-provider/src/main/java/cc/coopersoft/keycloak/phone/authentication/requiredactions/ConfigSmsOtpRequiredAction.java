package cc.coopersoft.keycloak.phone.authentication.requiredactions;

import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialModel;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProvider;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProviderFactory;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.credential.CredentialProvider;

import jakarta.ws.rs.core.Response;

public class ConfigSmsOtpRequiredAction implements RequiredActionProvider {

    public static final String PROVIDER_ID = "CONFIGURE_SMS_OTP";

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        Response challenge = context.form()
                .createForm("login-sms-otp-config.ftl");
        context.challenge(challenge);
    }

    @Override
    public void processAction(RequiredActionContext context) {
        TokenCodeService tokenCodeService = context.getSession().getProvider(TokenCodeService.class);
        PhoneNumber phoneNumber = new PhoneNumber(context.getHttpRequest().getDecodedFormParameters());
        String code = context.getHttpRequest().getDecodedFormParameters().getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
        if (tokenCodeService.validateCode(context.getUser(), phoneNumber, code)) {
            PhoneOtpCredentialProvider socp = (PhoneOtpCredentialProvider) context.getSession()
                    .getProvider(CredentialProvider.class, PhoneOtpCredentialProviderFactory.PROVIDER_ID);
            socp.createCredential(context.getRealm(), context.getUser(), PhoneOtpCredentialModel.create(phoneNumber));
            context.success();
        } else {
            Response challenge = context.form()
                    .setAttribute("areaCode", phoneNumber.getAreaCode())
                    .setAttribute("phoneNumber", phoneNumber.getPhoneNumber())
                    .setError("verificationCodeDoesNotMatch")
                    .createForm("login-update-phone-number.ftl");
            context.challenge(challenge);
        }
    }

    @Override
    public void close() {
    }
}
