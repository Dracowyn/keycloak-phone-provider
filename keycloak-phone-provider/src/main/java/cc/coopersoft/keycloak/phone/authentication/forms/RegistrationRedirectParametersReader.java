package cc.coopersoft.keycloak.phone.authentication.forms;

import cc.coopersoft.keycloak.phone.utils.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.FormActionFactory;
import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RegistrationRedirectParametersReader implements FormActionFactory, FormAction {

    private static final Logger logger = Logger.getLogger(RegistrationRedirectParametersReader.class);

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    public static final String PROVIDER_ID = "registration-redirect-parameter";

    public static final String PARAM_NAMES = "registration.parameter.accept";

    static {
        ProviderConfigProperty acceptParamName;
        acceptParamName = new ProviderConfigProperty();
        acceptParamName.setName(PARAM_NAMES);
        acceptParamName.setLabel("Accept query param");
        acceptParamName.setType(ProviderConfigProperty.MULTIVALUED_STRING_TYPE);
        acceptParamName.setHelpText("Registration query param accept names.");
        CONFIG_PROPERTIES.add(acceptParamName);
    }

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED, AuthenticationExecutionModel.Requirement.DISABLED};

    private static final String[] QUERY_PARAM_BLACKLIST = {
            "execution",
            "session_code",
            "client_id",
            "tab_id",
            "nonce",
            "response_type",
            "response_mode",
            "scope",
            "redirect_uri",
            "state",
            "phoneNumber",
            "phoneNumberVerified"
    };

    @Override
    public String getDisplayType() {
        return "Redirect parameter reader";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
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
    public String getHelpText() {
        return "Read query parameter add to user attribute";
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
    }

    @Override
    public void validate(ValidationContext validationContext) {
        validationContext.success();
    }

    @Override
    public void success(FormContext context) {


        String redirectUri = context.getAuthenticationSession().getRedirectUri();
        logger.info("add user attribute form redirectUri:" + redirectUri);
        if (StringUtils.isBlank(redirectUri)) {
            logger.error("no referer. cant get param in keycloak version");
            return;
        }

        Map<String, List<String>> queryParams = parseQueryParameters(redirectUri);
        if (!queryParams.isEmpty()) {
            UserModel user = context.getUser();
            String[] paramNames = null;
            AuthenticatorConfigModel authenticatorConfig = context.getAuthenticatorConfig();
            if (authenticatorConfig != null && authenticatorConfig.getConfig() != null) {
                paramNames = Optional.ofNullable(context.getAuthenticatorConfig().getConfig().get(PARAM_NAMES)).orElse("").split("##");
            }
            String[] finalParamNames = paramNames;
            logger.info("allow query param names:" + Arrays.toString(finalParamNames));
            queryParams.keySet()
                    .stream()
                    .filter(v -> (finalParamNames != null && finalParamNames.length > 0) ? Arrays.asList(finalParamNames).contains(v) : !StringUtils.isBlank(v) && v.length() < 32 && Arrays.stream(QUERY_PARAM_BLACKLIST).noneMatch(item -> item.equals(v)))

                    .forEach(v -> user.setAttribute(v, queryParams.get(v)));

        }
    }

    /**
     * 从 redirect_uri 中解析查询参数，返回 参数名 -> 值列表（保留出现顺序，支持同名多值）。
     * 用 JDK 原生实现替代 okhttp 的 HttpUrl，避免为单次 URL 解析引入整个 okhttp + kotlin 运行时。
     * 对任意 scheme（含移动端自定义 scheme）与解析异常均做容错，无法解析时返回空 Map。
     */
    private static Map<String, List<String>> parseQueryParameters(String uri) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (uri == null) {
            return params;
        }
        int queryStart = uri.indexOf('?');
        if (queryStart < 0 || queryStart == uri.length() - 1) {
            return params;
        }
        String query = uri.substring(queryStart + 1);
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            query = query.substring(0, fragmentStart);
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawName = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            String name = safeUrlDecode(rawName);
            String value = safeUrlDecode(rawValue);
            if (name.isEmpty()) {
                continue;
            }
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    /**
     * URL 解码，容错处理非法百分号编码（如落单的 %、%zz）。
     * 与 okhttp HttpUrl 的宽松行为保持一致：无法解码时保留原始字面量，
     * 避免因 redirect_uri 中含未转义的 % 而抛异常中断注册流程。
     */
    private static String safeUrlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {

    }
}
