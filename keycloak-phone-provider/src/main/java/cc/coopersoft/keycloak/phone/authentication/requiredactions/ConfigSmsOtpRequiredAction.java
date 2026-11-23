package cc.coopersoft.keycloak.phone.authentication.requiredactions;

import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialModel;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProvider;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProviderFactory;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.PhoneNumberInput;
import cc.coopersoft.keycloak.phone.utils.StringUtils;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.models.UserModel;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

public class ConfigSmsOtpRequiredAction implements RequiredActionProvider {

    public static final String PROVIDER_ID = "CONFIGURE_SMS_OTP";
    static final String CONFIG_FORM = "login-sms-otp-config.ftl";
    static final String ERROR_CODE_MISMATCH = "verificationCodeDoesNotMatch";
    static final String ERROR_PHONE_NUMBER_MISMATCH = "smsOtpPhoneNumberMismatch";

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        Response challenge = context.form()
                .createForm(CONFIG_FORM);
        context.challenge(challenge);
    }

    @Override
    public void processAction(RequiredActionContext context) {
        TokenCodeService tokenCodeService = context.getSession().getProvider(TokenCodeService.class);
        UserModel user = context.getUser();
        PhoneNumber rawPhoneNumber = new PhoneNumber(context.getHttpRequest().getDecodedFormParameters());
        String code = context.getHttpRequest().getDecodedFormParameters().getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
        if (rawPhoneNumber.isEmpty() || StringUtils.isBlank(code)) {
            challengeWithError(context, rawPhoneNumber, ERROR_CODE_MISMATCH);
            return;
        }
        Optional<PhoneNumber> normalized = PhoneNumberInput.normalize(rawPhoneNumber);
        if (normalized.isEmpty()) {
            challengeWithError(context, rawPhoneNumber, PhoneConstants.INVALID_PHONE_NUMBER);
            return;
        }
        PhoneNumber phoneNumber = normalized.get();

        String currentPhoneNumber = user.getFirstAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER);
        boolean hasVerifiedPhone = VerifyPhoneOrEmail.isPhoneNumberVerified(currentPhoneNumber,
                user.getFirstAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER_VERIFIED));

        if (hasVerifiedPhone) {
            // 已有已验证号码：只能用该号码开启短信 MFA，换号须走更新手机号流程，
            // 防止仅掌握密码的人借此把第二因子换成自己的号码
            if (!currentPhoneNumber.equals(phoneNumber.getFullPhoneNumber())) {
                challengeWithError(context, phoneNumber, ERROR_PHONE_NUMBER_MISMATCH);
                return;
            }
            if (!tokenCodeService.validateCode(user, phoneNumber, code)) {
                challengeWithError(context, phoneNumber, ERROR_CODE_MISMATCH);
                return;
            }
        } else {
            // 无号码或号码未验证：验证码通过即绑定该号码（同号码其他账户取消验证）
            try {
                tokenCodeService.setUserPhoneNumberByCode(user, phoneNumber, code);
            } catch (BadRequestException | ForbiddenException e) {
                challengeWithError(context, phoneNumber, ERROR_CODE_MISMATCH);
                return;
            }
        }

        PhoneOtpCredentialProvider socp = (PhoneOtpCredentialProvider) context.getSession()
                .getProvider(CredentialProvider.class, PhoneOtpCredentialProviderFactory.PROVIDER_ID);
        socp.createCredential(context.getRealm(), user, PhoneOtpCredentialModel.create(phoneNumber));
        context.success();
    }

    private void challengeWithError(RequiredActionContext context, PhoneNumber phoneNumber, String error) {
        Response challenge = context.form()
                .setAttribute("areaCode", phoneNumber.getAreaCode())
                .setAttribute(PhoneConstants.FIELD_PHONE_NUMBER, phoneNumber.getPhoneNumber())
                .setError(error)
                .createForm(CONFIG_FORM);
        context.challenge(challenge);
    }

    @Override
    public void close() {
    }
}
