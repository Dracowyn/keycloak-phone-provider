package cc.coopersoft.keycloak.phone.authentication.requiredactions;

import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import org.jboss.logging.Logger;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.requiredactions.VerifyEmail;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

public class VerifyPhoneOrEmail extends VerifyEmail implements RequiredActionProvider,
        RequiredActionFactory {
    public static String PROVIDER_ID = "VERIFY_PHONE_OR_EMAIL";
    private static final Logger logger = Logger.getLogger(VerifyPhoneOrEmail.class);
    private static final VerifyPhoneOrEmail INSTANCE = new VerifyPhoneOrEmail();

    @Override
    public String getDisplayText() {
        return "Verify Phone or Email";
    }

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return INSTANCE;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // 只有当用户邮箱未验证且手机号也未验证时，才需要验证手机或邮箱
        if (!context.getUser().isEmailVerified() && !isUserPhoneNumberVerified(context.getUser())) {
            context.getUser().addRequiredAction(PROVIDER_ID);
            logger.debug("User is required to verify phone or email");
        }
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        if (isUserPhoneNumberVerified(context.getUser())) {
            context.success();
            authSession.removeAuthNote(Constants.VERIFY_EMAIL_KEY);
            return;
        }

        super.requiredActionChallenge(context);
    }

    private boolean isUserPhoneNumberVerified(UserModel user) {
        return isPhoneNumberVerified(user.getFirstAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER),
                user.getFirstAttribute("phoneNumberVerified"));
    }

    /**
     * 号码存在且 {@code phoneNumberVerified=true} 才算已验证。
     * 同号码被其他账户重新验证后，原账户只会被置为 {@code phoneNumberVerified=false} 而保留号码，
     * 只看号码是否存在会把这类账户误判为已验证。
     */
    static boolean isPhoneNumberVerified(String phoneNumber, String phoneNumberVerified) {
        return phoneNumber != null && !phoneNumber.trim().isEmpty() && "true".equals(phoneNumberVerified);
    }

    @Override
    public void close() {

    }
}
