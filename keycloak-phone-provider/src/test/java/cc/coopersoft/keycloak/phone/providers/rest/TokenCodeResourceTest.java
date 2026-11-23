package cc.coopersoft.keycloak.phone.providers.rest;

import cc.coopersoft.keycloak.phone.authentication.authenticators.directgrant.EverybodyPhoneAuthenticatorFactory;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaServiceProviderFactory;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderFactory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.keycloak.models.AuthenticationExecutionModel.Requirement.ALTERNATIVE;
import static org.keycloak.models.AuthenticationExecutionModel.Requirement.DISABLED;
import static org.keycloak.models.AuthenticationExecutionModel.Requirement.REQUIRED;

class TokenCodeResourceTest {

    @Test
    @DisplayName("当前 realm 没有自动开户执行器时不允许新号码 OTP")
    void missingAuthenticator_doesNotEnableEverybodyPhoneAuthentication() {
        RealmModel realm = realm(Map.of("root", List.of(execution("other-authenticator", REQUIRED))));

        assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(realm)).isFalse();
    }

    @Test
    @DisplayName("已启用的自动开户执行器允许新号码 OTP")
    void enabledAuthenticator_enablesEverybodyPhoneAuthentication() {
        for (AuthenticationExecutionModel.Requirement requirement : List.of(REQUIRED, ALTERNATIVE)) {
            RealmModel realm = realm(Map.of("root", List.of(everybody(requirement))));

            assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(realm)).isTrue();
        }
    }

    @Test
    @DisplayName("禁用的自动开户执行器不允许新号码 OTP")
    void disabledAuthenticator_doesNotEnableEverybodyPhoneAuthentication() {
        RealmModel realm = realm(Map.of("root", List.of(everybody(DISABLED))));

        assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(realm)).isFalse();
    }

    @Test
    @DisplayName("自动开户执行器位于启用子流程中时有效")
    void enabledSubflow_enablesEverybodyPhoneAuthentication() {
        RealmModel realm = realm(Map.of(
                "root", List.of(subflow("nested", REQUIRED)),
                "nested", List.of(everybody(REQUIRED))));

        assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(realm)).isTrue();
    }

    @Test
    @DisplayName("父流程禁用时其内部自动开户执行器不生效")
    void disabledSubflow_doesNotEnableEverybodyPhoneAuthentication() {
        RealmModel realm = realm(Map.of(
                "root", List.of(subflow("nested", DISABLED)),
                "nested", List.of(everybody(REQUIRED))));

        assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(realm)).isFalse();
    }

    @Test
    @DisplayName("另一个 realm 启用自动开户不会影响当前 realm")
    void realms_areIsolated() {
        RealmModel enabledRealm = realm(Map.of("root", List.of(everybody(REQUIRED))));
        RealmModel ordinaryRealm = realm(Map.of("root", List.of()));

        assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(enabledRealm)).isTrue();
        assertThat(TokenCodeResource.isEverybodyPhoneAuthenticationEnabled(ordinaryRealm)).isFalse();
    }

    @Test
    @DisplayName("已部署人机验证实现但没有默认服务商时拒绝发送（CAPTCHA_REQUIRED）")
    void deployedCaptchaWithoutDefaultProvider_rejects() {
        ProviderFactory deployed = (ProviderFactory) Proxy.newProxyInstance(
                CaptchaServiceProviderFactory.class.getClassLoader(),
                new Class<?>[]{CaptchaServiceProviderFactory.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        KeycloakSession session = sessionWithoutDefaultCaptcha(List.of(deployed, deployed));

        assertThat(TokenCodeResource.passesCaptcha(session, null, new MultivaluedHashMap<>())).isFalse();
    }

    @Test
    @DisplayName("一个人机验证实现都没部署时跳过人机验证")
    void noCaptchaDeployed_skipsCaptcha() {
        KeycloakSession session = sessionWithoutDefaultCaptcha(List.of());

        assertThat(TokenCodeResource.passesCaptcha(session, null, new MultivaluedHashMap<>())).isTrue();
    }

    /** 取不到默认 CaptchaService、但部署了给定 factory 的会话桩。 */
    private static KeycloakSession sessionWithoutDefaultCaptcha(List<ProviderFactory> deployedFactories) {
        KeycloakSessionFactory factory = (KeycloakSessionFactory) Proxy.newProxyInstance(
                KeycloakSessionFactory.class.getClassLoader(),
                new Class<?>[]{KeycloakSessionFactory.class}, (proxy, method, args) -> {
                    if ("getProviderFactoriesStream".equals(method.getName())) {
                        return deployedFactories.stream();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (KeycloakSession) Proxy.newProxyInstance(KeycloakSession.class.getClassLoader(),
                new Class<?>[]{KeycloakSession.class}, (proxy, method, args) -> {
                    if ("getProvider".equals(method.getName()) && args.length == 1) {
                        return null;
                    }
                    if ("getKeycloakSessionFactory".equals(method.getName())) {
                        return factory;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AuthenticationExecutionModel everybody(AuthenticationExecutionModel.Requirement requirement) {
        return execution(EverybodyPhoneAuthenticatorFactory.PROVIDER_ID, requirement);
    }

    private static AuthenticationExecutionModel execution(String authenticator,
                                                          AuthenticationExecutionModel.Requirement requirement) {
        AuthenticationExecutionModel execution = new AuthenticationExecutionModel();
        execution.setAuthenticator(authenticator);
        execution.setRequirement(requirement);
        return execution;
    }

    private static AuthenticationExecutionModel subflow(String flowId,
                                                        AuthenticationExecutionModel.Requirement requirement) {
        AuthenticationExecutionModel execution = execution(null, requirement);
        execution.setAuthenticatorFlow(true);
        execution.setFlowId(flowId);
        return execution;
    }

    private static RealmModel realm(Map<String, List<AuthenticationExecutionModel>> executions) {
        return (RealmModel) Proxy.newProxyInstance(RealmModel.class.getClassLoader(),
                new Class<?>[]{RealmModel.class}, (proxy, method, args) -> {
                    if ("getAuthenticationFlowsStream".equals(method.getName())) {
                        return executions.keySet().stream().map(id -> {
                            AuthenticationFlowModel flow = new AuthenticationFlowModel();
                            flow.setId(id);
                            flow.setTopLevel("root".equals(id));
                            return flow;
                        });
                    }
                    if ("getAuthenticationExecutionsStream".equals(method.getName())) {
                        return executions.getOrDefault((String) args[0], List.of()).stream();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
