package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import cc.coopersoft.keycloak.phone.authentication.requiredactions.ConfigSmsOtpRequiredAction;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProvider;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProviderFactory;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneMessageService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.CredentialValidator;
import org.keycloak.common.util.Time;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.models.*;
import org.keycloak.services.Urls;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.net.URI;

public class SmsOtpMfaAuthenticator implements Authenticator, CredentialValidator<PhoneOtpCredentialProvider> {

    private static final Logger logger = Logger.getLogger(SmsOtpMfaAuthenticator.class);

    static final String COOKIE_NAME = SmsOtpRememberToken.TOKEN_TYPE;
    static final String CONFIG_COOKIE_MAX_AGE = "cookie.max.age";
    // 1 hour
    static final int DEFAULT_COOKIE_MAX_AGE = 60 * 60;

    /**
     * 校验免验证 Cookie：必须是本 realm 签发、属于当前用户、绑定的短信 OTP 凭据仍存在且未过期的签名令牌。
     * 只判断 Cookie 是否存在会让任何人伪造 {@code SMS_OTP_ANSWERED=任意值} 跳过短信第二因子。
     */
    protected boolean hasValidCookie(AuthenticationFlowContext context) {
        Cookie cookie = context.getHttpRequest()
                .getHttpHeaders()
                .getCookies()
                .get(COOKIE_NAME);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isEmpty()) {
            return false;
        }

        KeycloakSession session = context.getSession();
        SmsOtpRememberToken token = session.tokens().decode(cookie.getValue(), SmsOtpRememberToken.class);
        if (token == null) {
            logger.debugf("Ignoring %s cookie with invalid signature", COOKIE_NAME);
            return false;
        }

        UserModel user = context.getUser();
        String credentialId = token.getCredentialId();
        if (!token.matches(realmIssuer(context), user.getId(), credentialId, Time.currentTime())) {
            return false;
        }
        CredentialModel credential = credentialId == null ? null
                : user.credentialManager().getStoredCredentialById(credentialId);
        return credential != null && getType(session).equals(credential.getType());
    }

    private static String realmIssuer(AuthenticationFlowContext context) {
        return Urls.realmIssuer(context.getUriInfo().getBaseUri(), context.getRealm().getName());
    }

    /**
     * 解析 Cookie 有效期配置，缺失、非数字或非正数时回退到默认值。
     */
    static int parseCookieMaxAge(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_COOKIE_MAX_AGE;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : DEFAULT_COOKIE_MAX_AGE;
        } catch (NumberFormatException e) {
            logger.warnf("Invalid %s value '%s', using default %d", CONFIG_COOKIE_MAX_AGE, configured, DEFAULT_COOKIE_MAX_AGE);
            return DEFAULT_COOKIE_MAX_AGE;
        }
    }

    /**
     * 校验短信验证码。
     *
     * @return 校验通过的凭据 ID；未通过时返回 {@code null}
     */
    protected String validateAnswer(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String secret = formData.getFirst(PhoneConstants.FIELD_VERIFICATION_CODE);
        String credentialId = formData.getFirst("credentialId");
        if (credentialId == null || credentialId.isEmpty()) {
            CredentialModel defaultCredential = getCredentialProvider(context.getSession())
                    .getDefaultCredential(context.getSession(), context.getRealm(), context.getUser());
            if (defaultCredential == null) {
                return null;
            }
            credentialId = defaultCredential.getId();
        } else {
            // 表单提交的凭据 ID 必须是当前用户自己的短信 OTP 凭据，免验证 Cookie 会绑定到它
            CredentialModel submitted = context.getUser().credentialManager().getStoredCredentialById(credentialId);
            if (submitted == null || !getType(context.getSession()).equals(submitted.getType())) {
                return null;
            }
        }

        UserCredentialModel input = new UserCredentialModel(credentialId, getType(context.getSession()), secret);
        return getCredentialProvider(context.getSession()).isValid(context.getRealm(), context.getUser(), input)
                ? credentialId : null;
    }

    protected void setCookie(AuthenticationFlowContext context, String credentialId) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        int maxCookieAge = parseCookieMaxAge(config == null ? null : config.getConfig().get(CONFIG_COOKIE_MAX_AGE));

        URI baseUri = context.getUriInfo().getBaseUri();
        URI uri = context.getUriInfo()
                .getBaseUriBuilder()
                .path("realms")
                .path(context.getRealm().getName())
                .build();

        SmsOtpRememberToken token = new SmsOtpRememberToken(realmIssuer(context), context.getUser().getId(),
                credentialId, Time.currentTime(), maxCookieAge);
        String value = context.getSession().tokens().encode(token);

        context.getSession().getContext().getHttpResponse().addHeader(HttpHeaders.SET_COOKIE,
                buildCookieHeader(COOKIE_NAME, value, uri.getRawPath(), maxCookieAge,
                        "https".equalsIgnoreCase(baseUri.getScheme())));
    }

    /**
     * 构建 {@code Set-Cookie} 头：HttpOnly、SameSite=Lax，HTTPS 下附带 Secure。
     */
    static String buildCookieHeader(String name, String value, String path, int maxAge, boolean secure) {
        StringBuilder cookieBuilder = new StringBuilder();
        cookieBuilder.append(name).append("=").append(value);
        if (path != null) {
            cookieBuilder.append("; Path=").append(path);
        }
        cookieBuilder.append("; Max-Age=").append(maxAge);
        if (secure) {
            cookieBuilder.append("; Secure");
        }
        cookieBuilder.append("; HttpOnly; SameSite=Lax");
        return cookieBuilder.toString();
    }

    @Override
    public PhoneOtpCredentialProvider getCredentialProvider(KeycloakSession session) {
        return (PhoneOtpCredentialProvider) session.getProvider(CredentialProvider.class, PhoneOtpCredentialProviderFactory.PROVIDER_ID);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (hasValidCookie(context)) {
            context.success();
            return;
        }
        PhoneMessageService phoneMessageService = context.getSession().getProvider(PhoneMessageService.class);
        PhoneNumber phoneNumber = new PhoneNumber(context.getUser().getFirstAttribute("phoneNumber"));
        Response challenge;
        try {
            phoneMessageService.sendTokenCode(phoneNumber, TokenCodeType.OTP);
            challenge = context.form().createForm("login-sms-otp.ftl");
        } catch (ForbiddenException e) {
            challenge = context.form().setError("abusedMessageService").createForm("login-sms-otp.ftl");
        }
        context.challenge(challenge);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        String credentialId = validateAnswer(context);
        if (credentialId == null) {
            Response challenge = context.form()
                    .setError("authenticationCodeDoesNotMatch")
                    .createForm("login-sms-otp.ftl");
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
            return;
        }
        setCookie(context, credentialId);
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return getCredentialProvider(session).isConfiguredFor(realm, user, getType(session));
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        user.addRequiredAction(ConfigSmsOtpRequiredAction.PROVIDER_ID);
    }

    @Override
    public void close() {

    }
}
