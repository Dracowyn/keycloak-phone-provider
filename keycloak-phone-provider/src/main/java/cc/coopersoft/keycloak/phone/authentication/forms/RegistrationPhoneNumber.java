/*
  add by zhangzhl
  2020-07-27
  注册页手机号码必填验证
 */
package cc.coopersoft.keycloak.phone.authentication.forms;

import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.keycloak.Config;
import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.FormActionFactory;
import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.provider.ProviderConfigProperty;

import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.List;

public class RegistrationPhoneNumber implements FormAction, FormActionFactory {

    public static final String PROVIDER_ID = "registration-phone";

    @Override
    public String getHelpText() {
        return "valid phone number and verification code";
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
        return "Phone Validation";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED, AuthenticationExecutionModel.Requirement.DISABLED};

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

    /**
     * 验证手机号码和验证码
     *
     * @param context 验证上下文对象
     */
    @Override
    public void validate(ValidationContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        List<FormMessage> errors = new ArrayList<>();
        context.getEvent().detail(Details.REGISTER_METHOD, "form");

        KeycloakSession session = context.getSession();

        PhoneNumber phoneNumber = new PhoneNumber(formData);
        context.getEvent().detail(PhoneConstants.FIELD_PHONE_NUMBER, phoneNumber.getFullPhoneNumber());

        if (phoneNumber.isEmpty()) {
            context.error(Errors.INVALID_REGISTRATION);
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, PhoneConstants.MISSING_PHONE_NUMBER));
            context.validationError(formData, errors);
            return;
        }

        if (UserUtils.isPhoneNumberInUse(session.users(), context.getRealm(), phoneNumber)) {
            formData.remove(PhoneConstants.FIELD_PHONE_NUMBER);
            context.getEvent().detail(PhoneConstants.FIELD_PHONE_NUMBER, phoneNumber.getFullPhoneNumber());
            errors.add(new FormMessage(PhoneConstants.FIELD_PHONE_NUMBER, PhoneConstants.PHONE_EXISTS));
            context.error(Errors.INVALID_REGISTRATION);
            context.validationError(formData, errors);
            return;
        }

        String verificationCode = formData.getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
        // 只校验不消费；success() 中按 ID 消费，避免其他字段校验失败时验证码已作废
        if (!RegistrationSmsCode.check(session, phoneNumber, verificationCode)) {
            context.error(Errors.INVALID_REGISTRATION);
            formData.remove(PhoneConstants.FIELD_VERIFICATION_CODE);
            errors.add(new FormMessage(PhoneConstants.FIELD_VERIFICATION_CODE, PhoneConstants.SMS_CODE_MISMATCH));
            context.validationError(formData, errors);
            return;
        }

        context.success();
    }

    @Override
    public void success(FormContext context) {
        UserModel user = context.getUser();

        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();

        PhoneNumber phoneNumber = new PhoneNumber(formData);
        RegistrationSmsCode.consume(context.getSession(), user, phoneNumber);
    }

    @Override
    public void buildPage(FormContext context, LoginFormsProvider form) {
        form.setAttribute("phoneNumberRequired", true);
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
