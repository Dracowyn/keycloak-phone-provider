package cc.coopersoft.keycloak.phone.authentication.authenticators.directgrant;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.authentication.ConfigurableAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.ArrayList;
import java.util.List;

public class AuthenticationCodeAuthenticatorFactory implements AuthenticatorFactory, ConfigurableAuthenticatorFactory {
    public static final String MAX_AGE = "verificationCode.max.age";
    public static final String KIND = "verificationCode.kind";

    public static final String PROVIDER_ID = "verification-code-authenticator";

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

    @Override
    public Authenticator create(KeycloakSession session) {
        return new AuthenticationCodeAuthenticator(session);
    }

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };
    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    static {
        ProviderConfigProperty maxAge;
        maxAge = new ProviderConfigProperty();
        maxAge.setName(MAX_AGE);
        maxAge.setLabel("Verification Code Max Age");
        maxAge.setType(ProviderConfigProperty.STRING_TYPE);
        maxAge.setHelpText("Max age in seconds of the verification codes.");
        CONFIG_PROPERTIES.add(maxAge);
    }

    @Override
    public String getDisplayType() {
        return "Provide verification code";
    }

    @Override
    public String getHelpText() {
        return "Provide verification code";
    }

    @Override
    public String getReferenceCategory() {
        return "Verification Code Grant";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }
}

