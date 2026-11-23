package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import org.keycloak.authentication.AbstractFormAuthenticator;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

public class PhoneLoginForm extends AbstractFormAuthenticator implements Authenticator {

    public static final String PHONE_LOGIN_FORM_TPL = "login-with-phone.ftl";

    public static final String FIELD_LOGIN_TYPE = PhoneLoginFormSupport.FIELD_LOGIN_TYPE;

    public static final String USER_NOT_EXISTS = PhoneLoginFormSupport.USER_NOT_EXISTS;

    /**
     * @deprecated 手机号登录分支已不调用此方法，覆盖不再生效，后续版本移除。
     */
    @Deprecated(forRemoval = true)
    protected Response challenge(AuthenticationFlowContext context, String error,
                                 MultivaluedMap<String, String> formData) {
        return PhoneLoginFormSupport.challenge(context, PHONE_LOGIN_FORM_TPL, error, formData);
    }

    /**
     * @deprecated 手机号登录分支已不调用此方法，覆盖不再生效，后续版本移除。
     */
    @Deprecated(forRemoval = true)
    protected Response makeForm(LoginFormsProvider form) {
        return PhoneLoginFormSupport.makeForm(form, PHONE_LOGIN_FORM_TPL);
    }

    /**
     * @deprecated 手机号登录分支已不调用此方法，覆盖不再生效，后续版本移除。
     */
    @Deprecated(forRemoval = true)
    protected Response makeForm(LoginFormsProvider form, MultivaluedMap<String, String> formData) {
        return PhoneLoginFormSupport.makeForm(form, PHONE_LOGIN_FORM_TPL, formData);
    }

    /**
     * @deprecated 手机号登录分支已不调用此方法，覆盖不再生效，后续版本移除。
     */
    @Deprecated(forRemoval = true)
    public boolean enabledUser(AuthenticationFlowContext context, UserModel user,
                               MultivaluedMap<String, String> formData) {
        return PhoneLoginFormSupport.enabledUser(context, user, formData, PHONE_LOGIN_FORM_TPL);
    }

    /**
     * @deprecated 手机号登录分支已不调用此方法，覆盖不再生效，后续版本移除。
     */
    @Deprecated(forRemoval = true)
    protected boolean isTemporarilyDisabledByBruteForce(AuthenticationFlowContext context, UserModel user,
                                                        MultivaluedMap<String, String> formData) {
        return PhoneLoginFormSupport.isTemporarilyDisabledByBruteForce(context, user, formData, PHONE_LOGIN_FORM_TPL);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        if (!formData.containsKey(FIELD_LOGIN_TYPE)
                || !formData.getFirst(FIELD_LOGIN_TYPE).equals("phone")) {
            context.attempted();
            return;
        }

        PhoneLoginFormSupport.performPhoneLogin(context, formData, PHONE_LOGIN_FORM_TPL);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        PhoneLoginFormSupport.authenticate(context, PHONE_LOGIN_FORM_TPL);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // never called
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // never called
    }

    @Override
    public void close() {

    }
}
