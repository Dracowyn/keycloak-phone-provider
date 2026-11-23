package cc.coopersoft.keycloak.phone.credential;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneOtpCredentialProviderTest {

    @Test
    @DisplayName("短信 OTP 按凭据登记的号码校验，不跟随被改动的 phoneNumber 属性；旧凭据没有号码时回退到属性")
    void isValid_usesRegisteredNumber_fallsBackToAttributeForLegacyCredential() {
        List<String> validated = new ArrayList<>();
        PhoneOtpCredentialProvider provider = new PhoneOtpCredentialProvider(session(validated));
        UserModel user = user("+86 13700000000", Map.of(
                "registered", credential("registered", "{\"areaCode\":\"86\",\"phoneNumber\":\"13900000001\"}"),
                "legacy", credential("legacy", "{}")));

        assertThat(provider.isValid(null, user,
                new UserCredentialModel("registered", PhoneOtpCredentialModel.TYPE, "123456"))).isTrue();
        assertThat(provider.isValid(null, user,
                new UserCredentialModel("legacy", PhoneOtpCredentialModel.TYPE, "123456"))).isTrue();

        assertThat(validated).containsExactly("+86 13900000001", "+86 13700000000");
    }

    /** 只支持取 TokenCodeService 的会话桩；validateCode(号码, 验证码, OTP) 记录号码并返回通过。 */
    private static KeycloakSession session(List<String> validated) {
        TokenCodeService tokenCodes = (TokenCodeService) Proxy.newProxyInstance(
                TokenCodeService.class.getClassLoader(), new Class<?>[]{TokenCodeService.class},
                (proxy, method, args) -> {
                    if ("validateCode".equals(method.getName()) && args.length == 3
                            && args[0] instanceof PhoneNumber phoneNumber && args[2] == TokenCodeType.OTP) {
                        validated.add(phoneNumber.getFullPhoneNumber());
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (KeycloakSession) Proxy.newProxyInstance(KeycloakSession.class.getClassLoader(),
                new Class<?>[]{KeycloakSession.class}, (proxy, method, args) -> {
                    if ("getProvider".equals(method.getName()) && args.length == 1
                            && args[0] == TokenCodeService.class) {
                        return tokenCodes;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static UserModel user(String phoneAttribute, Map<String, CredentialModel> credentials) {
        SubjectCredentialManager manager = (SubjectCredentialManager) Proxy.newProxyInstance(
                SubjectCredentialManager.class.getClassLoader(), new Class<?>[]{SubjectCredentialManager.class},
                (proxy, method, args) -> {
                    if ("getStoredCredentialById".equals(method.getName())) {
                        return credentials.get((String) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (UserModel) Proxy.newProxyInstance(UserModel.class.getClassLoader(),
                new Class<?>[]{UserModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "user-a";
                    case "getFirstAttribute" -> "phoneNumber".equals(args[0]) ? phoneAttribute : null;
                    case "credentialManager" -> manager;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CredentialModel credential(String id, String credentialData) {
        CredentialModel credential = new CredentialModel();
        credential.setId(id);
        credential.setType(PhoneOtpCredentialModel.TYPE);
        credential.setCredentialData(credentialData);
        credential.setSecretData("{}");
        return credential;
    }
}
