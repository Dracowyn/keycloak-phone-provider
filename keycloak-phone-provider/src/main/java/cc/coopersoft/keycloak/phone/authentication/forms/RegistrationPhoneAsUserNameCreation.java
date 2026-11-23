package cc.coopersoft.keycloak.phone.authentication.forms;

import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.PhoneNumberInput;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.FormActionFactory;
import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.messages.Messages;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;

import jakarta.ws.rs.core.MultivaluedMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * replace  org.keycloak.authentication.forms.RegistrationUserCreation.java
 */
public class RegistrationPhoneAsUserNameCreation implements FormActionFactory, FormAction {

    private static final Logger logger = Logger.getLogger(RegistrationPhoneAsUserNameCreation.class);

    public static final String PROVIDER_ID = "registration-phone-username-creation";

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED, AuthenticationExecutionModel.Requirement.DISABLED};


    @Override
    public String getDisplayType() {
        return "Registration Phone As Username Creation";
    }

    @Override
    public String getHelpText() {
        return "This action must always be first And Do not use Email as username! registration phone number as username. In success phase, this will create the user in the database.";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }


    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }


    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return null;
    }

    @Override
    public FormAction create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    // FormAction

    @Override
    public void buildPage(FormContext formContext, LoginFormsProvider loginFormsProvider) {
        loginFormsProvider.setAttribute("registrationPhoneAsUsername", true);
    }

    @Override
    public void validate(ValidationContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        List<FormMessage> errors = new ArrayList<>();
        context.getEvent().detail(Details.REGISTER_METHOD, "form");

        PhoneNumber rawPhoneNumber = new PhoneNumber(formData);
        context.getEvent().detail(Details.USERNAME, rawPhoneNumber.getPhoneNumber());

        if (rawPhoneNumber.isEmpty()) {
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, PhoneConstants.MISSING_PHONE_NUMBER));
            context.error(Errors.INVALID_REGISTRATION);
            context.validationError(formData, errors);
            return;
        }
        Optional<PhoneNumber> normalized = PhoneNumberInput.normalize(rawPhoneNumber);
        if (normalized.isEmpty()) {
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, PhoneConstants.INVALID_PHONE_NUMBER));
            context.error(Errors.INVALID_REGISTRATION);
            context.validationError(formData, errors);
            return;
        }
        PhoneNumber phoneNumber = normalized.get();
        context.getEvent().detail(Details.USERNAME, phoneNumber.getPhoneNumber());

        if (UserUtils.isPhoneNumberInUse(context.getSession().users(), context.getRealm(), phoneNumber)) {
            context.error(Errors.INVALID_REGISTRATION);
            formData.remove(PhoneConstants.FIELD_PHONE_NUMBER);
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, PhoneConstants.PHONE_EXISTS));
            context.validationError(formData, errors);
            return;
        }

        if (context.getSession().users().getUserByUsername(context.getRealm(), phoneNumber.getPhoneNumber()) != null) {
            context.error(Errors.USERNAME_IN_USE);
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, Messages.USERNAME_EXISTS));
            formData.remove(PhoneConstants.FIELD_PHONE_NUMBER);
            context.validationError(formData, errors);
            return;
        }

        context.success();
    }

    @Override
    public void success(FormContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();

        // validate 阶段已校验过号码，用规范化后的纯数字号码，不用原样提交的值
        String username = PhoneNumberInput.fromForm(formData).map(PhoneNumber::getPhoneNumber)
                .orElseThrow(() -> new IllegalStateException("注册号码未通过校验却进入了 success 阶段"));

        context.getEvent().detail(Details.USERNAME, username)
                .detail(Details.REGISTER_METHOD, "phone");

        KeycloakSession session = context.getSession();

        UserProfileProvider profileProvider = session.getProvider(UserProfileProvider.class);
        UserProfile profile = profileProvider.create(UserProfileContext.REGISTRATION, formData);
        UserModel user = profile.create();

        user.setEnabled(true);

        context.setUser(user);

        context.getAuthenticationSession().setClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM, username);

        context.getEvent().user(user);
        context.getEvent().success();
        context.newEvent().event(EventType.LOGIN);
        context.getEvent().client(context.getAuthenticationSession().getClient().getClientId())
                .detail(Details.REDIRECT_URI, context.getAuthenticationSession().getRedirectUri())
                .detail(Details.AUTH_METHOD, context.getAuthenticationSession().getProtocol());
        String authType = context.getAuthenticationSession().getAuthNote(Details.AUTH_TYPE);
        if (authType != null) {
            context.getEvent().detail(Details.AUTH_TYPE, authType);
        }
        //logger.info(String.format("user: %s is created, user name is %s ",user.getId(), user.getUsername()));
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {
        return !realmModel.isRegistrationEmailAsUsername();
    }

    @Override
    public void setRequiredActions(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {

    }
}
