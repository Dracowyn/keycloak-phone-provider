package cc.coopersoft.keycloak.phone.authentication.forms;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.FormActionFactory;
import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.authentication.forms.RegistrationPage;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.policy.PasswordPolicyManagerProvider;
import org.keycloak.policy.PolicyError;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;

import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.List;

public class RegistrationPhoneNumberOrEmail implements FormAction, FormActionFactory {

    private static final Logger logger = Logger.getLogger(RegistrationPhoneNumberOrEmail.class);

    public static final String PROVIDER_ID = "registration-phone-or-email";

    public static final String MISSING_PHONE_NUMBER_OR_EMAIL = "requiredPhoneNumberOrEmail";
    public static final String PHONE_IN_USE = "phone_in_use";
    public static final String INVALID_SMS_CODE = "invalid_sms_code";

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public String getHelpText() {
        return "valid phone number and verification code or using email";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return null;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public void close() {
    }

    @Override
    public String getDisplayType() {
        return "Phone Or Email Validation";
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
    public String getId() {
        return PROVIDER_ID;
    }

    private TokenCodeService getTokenCodeService(KeycloakSession session) {
        return session.getProvider(TokenCodeService.class);
    }

    @Override
    public void validate(ValidationContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        List<FormMessage> errors = new ArrayList<>();

        context.getEvent().detail(Details.REGISTER_METHOD, "form");
        String eventError = Errors.INVALID_REGISTRATION;
        KeycloakSession session = context.getSession();
        String credentialType = formData.getFirst(PhoneConstants.FIELD_CREDENTIAL_TYPE);

        logger.debug("Credential type: " + credentialType);

        if (PhoneConstants.CREDENTIAL_TYPE_PHONE.equals(credentialType)) {
            validatePhoneRegistration(context, formData, errors, session, eventError);
        } else if (PhoneConstants.CREDENTIAL_TYPE_EMAIL.equals(credentialType)) {
            validateEmailRegistration(context, formData, errors, session, eventError);
        } else {
            // 缺少参数
            eventError = Errors.INVALID_INPUT;
            errors.add(new FormMessage(null, MISSING_PHONE_NUMBER_OR_EMAIL));
        }

        if (!errors.isEmpty()) {
            context.error(eventError);
            formData.remove(RegistrationPage.FIELD_PASSWORD);
            formData.remove(RegistrationPage.FIELD_PASSWORD_CONFIRM);
            context.validationError(formData, errors);
        } else {
            context.success();
        }
    }

    private void validatePhoneRegistration(ValidationContext context, MultivaluedMap<String, String> formData,
                                           List<FormMessage> errors, KeycloakSession session, String eventError) {
        // 使用手机号注册
        formData.remove(PhoneConstants.FIELD_EMAIL);
        PhoneNumber phoneNumber = new PhoneNumber(formData);
        context.getEvent().detail(PhoneConstants.FIELD_PHONE_NUMBER, phoneNumber.getFullPhoneNumber());

        // 验证手机号是否已被使用
        if (UserUtils.findUserByPhone(session.users(), context.getRealm(), phoneNumber) != null) {
            formData.remove(PhoneConstants.FIELD_PHONE_NUMBER);
            context.getEvent().detail(PhoneConstants.FIELD_PHONE_NUMBER, phoneNumber.getFullPhoneNumber());
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, PhoneConstants.PHONE_EXISTS));
            return;
        }

        // 验证短信验证码
        validateSmsCode(context, formData, errors, session, phoneNumber);
    }

    private void validateSmsCode(ValidationContext context, MultivaluedMap<String, String> formData,
                                 List<FormMessage> errors, KeycloakSession session, PhoneNumber phoneNumber) {
        String verificationCode = formData.getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
        // 只校验不消费（常量时间比较 + 失败计数）；success() 中 tokenValidated 消费，避免其他字段校验失败时验证码已作废
        TokenCodeRepresentation tokenCode = Validation.isBlank(verificationCode) ? null
                : getTokenCodeService(session).checkCode(phoneNumber, verificationCode, TokenCodeType.REGISTRATION);

        if (tokenCode == null) {
            context.error(INVALID_SMS_CODE);
            context.getEvent().detail(PhoneConstants.FIELD_PHONE_NUMBER, phoneNumber.getFullPhoneNumber());
            errors.add(new FormMessage(PhoneConstants.FIELD_VERIFICATION_CODE, PhoneConstants.SMS_CODE_MISMATCH));
            return;
        }

        context.getSession().setAttribute(PhoneConstants.FIELD_TOKEN_ID, tokenCode.getId());
    }

    private void validateEmailRegistration(ValidationContext context, MultivaluedMap<String, String> formData,
                                           List<FormMessage> errors, KeycloakSession session, String eventError) {
        // 使用邮箱注册
        formData.remove(PhoneConstants.FIELD_AREA_CODE);
        formData.remove(PhoneConstants.FIELD_PHONE_NUMBER);

        // 验证邮箱
        validateEmail(context, formData, errors, session);

        // 验证密码
        validatePassword(context, formData, errors);
    }

    private void validateEmail(ValidationContext context, MultivaluedMap<String, String> formData,
                               List<FormMessage> errors, KeycloakSession session) {
        String email = formData.getFirst(Validation.FIELD_EMAIL);

        // 验证邮箱格式
        if (Validation.isBlank(email)) {
            errors.add(new FormMessage(RegistrationPage.FIELD_EMAIL, Messages.MISSING_EMAIL));
            return;
        }

        if (!Validation.isEmailValid(email)) {
            context.getEvent().detail(Details.EMAIL, email);
            errors.add(new FormMessage(RegistrationPage.FIELD_EMAIL, Messages.INVALID_EMAIL));
            return;
        }

        // 验证邮箱是否重复
        if (!context.getRealm().isDuplicateEmailsAllowed()) {
            boolean duplicateEmail = false;
            try {
                if (session.users().getUserByEmail(context.getRealm(), email) != null) {
                    duplicateEmail = true;
                }
            } catch (ModelDuplicateException e) {
                duplicateEmail = true;
            }

            if (duplicateEmail) {
                formData.remove(Validation.FIELD_EMAIL);
                context.getEvent().detail(Details.EMAIL, email);
                errors.add(new FormMessage(RegistrationPage.FIELD_EMAIL, Messages.EMAIL_EXISTS));
            }
        }
    }

    private void validatePassword(ValidationContext context, MultivaluedMap<String, String> formData,
                                  List<FormMessage> errors) {
        String password = formData.getFirst(RegistrationPage.FIELD_PASSWORD);
        String passwordConfirm = formData.getFirst(RegistrationPage.FIELD_PASSWORD_CONFIRM);

        if (Validation.isBlank(password)) {
            errors.add(new FormMessage(RegistrationPage.FIELD_PASSWORD, Messages.MISSING_PASSWORD));
        } else if (!password.equals(passwordConfirm)) {
            errors.add(new FormMessage(RegistrationPage.FIELD_PASSWORD_CONFIRM, Messages.INVALID_PASSWORD_CONFIRM));
        }

        // 密码策略验证
        if (password != null) {
            String username = context.getRealm().isRegistrationEmailAsUsername() ?
                    formData.getFirst(RegistrationPage.FIELD_EMAIL) :
                    formData.getFirst(RegistrationPage.FIELD_USERNAME);

            PolicyError err = context.getSession().getProvider(PasswordPolicyManagerProvider.class)
                    .validate(username, password);

            if (err != null) {
                errors.add(new FormMessage(RegistrationPage.FIELD_PASSWORD, err.getMessage(), err.getParameters()));
            }
        }
    }

    @Override
    public void success(FormContext context) {
        UserModel user = context.getUser();
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String credentialType = formData.getFirst(PhoneConstants.FIELD_CREDENTIAL_TYPE);

        // 设置用户基本信息
        setUserBasicInfo(user, formData);

        if (PhoneConstants.CREDENTIAL_TYPE_PHONE.equals(credentialType)) {
            handlePhoneRegistrationSuccess(context, user, formData);
        } else {
            handleEmailRegistrationSuccess(user, formData);
        }
    }

    private void setUserBasicInfo(UserModel user, MultivaluedMap<String, String> formData) {
        if (formData.getFirst(RegistrationPage.FIELD_FIRST_NAME) != null) {
            user.setFirstName(formData.getFirst(RegistrationPage.FIELD_FIRST_NAME));
        }
        if (formData.getFirst(RegistrationPage.FIELD_LAST_NAME) != null) {
            user.setLastName(formData.getFirst(RegistrationPage.FIELD_LAST_NAME));
        }
    }

    private void handlePhoneRegistrationSuccess(FormContext context, UserModel user,
                                                MultivaluedMap<String, String> formData) {
        PhoneNumber phoneNumber = new PhoneNumber(formData);
        String tokenId = context.getSession().getAttribute(PhoneConstants.FIELD_TOKEN_ID, String.class);

        // 设置电子邮箱（如果有）
        String email = formData.getFirst(RegistrationPage.FIELD_EMAIL);
        if (email != null) {
            user.setEmail(email);
            user.setEmailVerified(false);
        }

        logger.info("Registration user " + tokenId + " phone success");
        getTokenCodeService(context.getSession()).tokenValidated(user, phoneNumber, tokenId);
    }

    private void handleEmailRegistrationSuccess(UserModel user, MultivaluedMap<String, String> formData) {
        logger.info("Registration user " + user.getUsername() + " by email success");
        user.setEmail(formData.getFirst(RegistrationPage.FIELD_EMAIL));

        try {
            user.credentialManager().updateCredential(
                    UserCredentialModel.password(formData.getFirst("password"), false));
        } catch (Exception me) {
            logger.warn("Failed to set password for user " + user.getUsername() + ", requiring password update action");
            user.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        }
    }

    @Override
    public void buildPage(FormContext context, LoginFormsProvider form) {
        form.setAttribute("phoneNumberRequired", true);
        form.setAttribute("passwordRequired", true);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }
}