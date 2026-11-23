package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.StringUtils;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.freemarker.model.LoginBean;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.messages.Messages;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/**
 * {@link PhoneLoginForm} 与 {@link PhoneOrPasswordLoginForm} 共用的手机号登录逻辑。
 * <p>
 * 两个认证器父类不同（无法合并继承），因此用组合委托到这里；模板名作为参数传入，
 * 其余表单属性、错误消息 key、调用顺序完全一致。
 */
final class PhoneLoginFormSupport {

    static final String FIELD_LOGIN_TYPE = "loginType";

    static final String USER_NOT_EXISTS = "userNotExists";

    private static final String FIELD_PASSWORD = "password";

    private static final String FIELD_PASSWORD_CONFIRM = "password-confirm";

    private static final String FIELD_REMEMBER_ME = "rememberMe";

    private PhoneLoginFormSupport() {
    }

    static Response challenge(AuthenticationFlowContext context, String template, String error,
                              MultivaluedMap<String, String> formData) {
        LoginFormsProvider form = context.form()
                .setExecution(context.getExecution().getId());
        if (error != null) {
            form.setError(error);
        }
        // 回显表单前移除敏感字段，避免密码/验证码随隐藏表单原样回显给浏览器，
        // 做法参考 Keycloak 官方 AbstractUsernameFormAuthenticator；这里对入参 formData 做防御性拷贝，不做原地修改。
        MultivaluedMap<String, String> safeFormData = new MultivaluedHashMap<>(formData);
        safeFormData.remove(FIELD_PASSWORD);
        safeFormData.remove(FIELD_PASSWORD_CONFIRM);
        safeFormData.remove(PhoneConstants.FIELD_VERIFICATION_CODE);
        return makeForm(form, template, safeFormData);
    }

    static Response makeForm(LoginFormsProvider form, String template) {
        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        return makeForm(form, template, formData);
    }

    static Response makeForm(LoginFormsProvider form, String template, MultivaluedMap<String, String> formData) {
        form.setAttribute("login", new LoginBean(formData));
        return form.createForm(template);
    }

    static boolean enabledUser(AuthenticationFlowContext context, UserModel user,
                               MultivaluedMap<String, String> formData, String template) {
        if (!user.isEnabled()) {
            context.getEvent().user(user);
            context.getEvent().error(Errors.USER_DISABLED);
            Response challengeResponse = challenge(context, template, Messages.ACCOUNT_DISABLED, formData);
            context.challenge(challengeResponse);
            return false;
        }
        return !isTemporarilyDisabledByBruteForce(context, user, formData, template);
    }

    static boolean isTemporarilyDisabledByBruteForce(AuthenticationFlowContext context, UserModel user,
                                                     MultivaluedMap<String, String> formData, String template) {
        if (context.getRealm().isBruteForceProtected()) {
            if (context.getProtector().isTemporarilyDisabled(context.getSession(), context.getRealm(), user)) {
                context.getEvent().user(user);
                context.getEvent().error(Errors.USER_TEMPORARILY_DISABLED);
                Response challengeResponse = challenge(context, template, Messages.INVALID_USER, formData);
                context.challenge(challengeResponse);
                return true;
            }
        }
        return false;
    }

    private static boolean validateUser(AuthenticationFlowContext context, UserModel user,
                                        MultivaluedMap<String, String> inputData, String template) {
        if (!enabledUser(context, user, inputData, template)) {
            return false;
        }
        String rememberMe = inputData.getFirst(FIELD_REMEMBER_ME);
        boolean remember = "on".equalsIgnoreCase(rememberMe);
        if (remember) {
            context.getAuthenticationSession().setAuthNote(Details.REMEMBER_ME, "true");
            context.getEvent().detail(Details.REMEMBER_ME, "true");
        } else {
            context.getAuthenticationSession().removeAuthNote(Details.REMEMBER_ME);
        }
        context.setUser(user);
        return true;
    }

    static void authenticate(AuthenticationFlowContext context, String template) {
        LoginFormsProvider form = context.form().setExecution(context.getExecution().getId());
        context.challenge(makeForm(form, template));
    }

    static void performPhoneLogin(AuthenticationFlowContext context, MultivaluedMap<String, String> formData,
                                  String template) {
        if (formData.containsKey("cancel")) {
            context.cancelLogin();
            return;
        }

        KeycloakSession session = context.getSession();

        PhoneNumber phoneNumber = new PhoneNumber(formData);
        if (phoneNumber.isEmpty()) {
            context.challenge(challenge(context, template, PhoneConstants.MISSING_PHONE_NUMBER, formData));
            return;
        }
        UserModel user = UserUtils.findUserByPhone(session.users(), context.getRealm(), phoneNumber);
        //用户不存在
        if (user == null) {
            context.challenge(challenge(context, template, USER_NOT_EXISTS, formData));
            return;
        }
        String code = formData.getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
        if (StringUtils.isBlank(code)) {
            context.challenge(challenge(context, template, PhoneConstants.MISSING_VERIFY_CODE, formData));
            return;
        }
        TokenCodeService tokenCodeService = session.getProvider(TokenCodeService.class);
        //验证码错误
        if (!tokenCodeService.validateCode(user, phoneNumber, code, TokenCodeType.LOGIN)) {
            context.challenge(challenge(context, template, PhoneConstants.SMS_CODE_MISMATCH, formData));
            return;
        }

        //一切OK，返回最终值
        if (validateUser(context, user, formData, template)) {
            context.success();
        }
    }
}
