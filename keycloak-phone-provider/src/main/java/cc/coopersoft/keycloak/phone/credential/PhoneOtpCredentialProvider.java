package cc.coopersoft.keycloak.phone.credential;

import cc.coopersoft.keycloak.phone.authentication.authenticators.browser.SmsOtpMfaAuthenticatorFactory;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.credential.*;
import org.keycloak.models.*;

public class PhoneOtpCredentialProvider implements CredentialProvider<PhoneOtpCredentialModel>, CredentialInputValidator {

    private final static Logger logger = Logger.getLogger(PhoneOtpCredentialProvider.class);
    private final KeycloakSession session;

    public PhoneOtpCredentialProvider(KeycloakSession session) {
        this.session = session;
    }

    /**
     * 必须用 {@code userModel.credentialManager()}：经用户缓存的 {@code UserModel}（如
     * {@code org.keycloak.models.cache.infinispan.UserAdapter}）返回的是
     * {@code SubjectCredentialManagerCacheAdapter}，它在创建/删除凭据时会调用
     * {@code invalidateCacheForEntity} 使该用户缓存失效，读取也走缓存中的凭据列表。
     * {@code session.users().getUserCredentialManager(user)} 返回裸的凭据管理器，绕过缓存失效，
     * 会导致新建的短信 OTP 凭据在缓存过期前对其他读取路径不可见（MFA 可能不被要求），
     * 已删除的凭据也会残留在缓存中。不要改回后者。
     */
    private SubjectCredentialManager getCredentialStore(UserModel userModel) {
        return userModel.credentialManager();
    }

    private TokenCodeService getTokenCodeService() {
        return session.getProvider(TokenCodeService.class);
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return getType().equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        if (!supportsCredentialType(credentialType)) {
            return false;
        }
        return getCredentialStore(user).getStoredCredentialsByTypeStream(credentialType).findAny().isPresent();
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {

        PhoneNumber phoneNumber = new PhoneNumber(user
                .getFirstAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER));
        String code = input.getChallengeResponse();

        if (!(input instanceof UserCredentialModel)) {
            return false;
        }
        if (!input.getType().equals(getType())) {
            return false;
        }
        if (phoneNumber.isEmpty()) {
            return false;
        }
        if (code == null) {
            return false;
        }

        return getTokenCodeService().validateCode(user, phoneNumber, code, TokenCodeType.OTP);
    }

    @Override
    public String getType() {
        return PhoneOtpCredentialModel.TYPE;
    }

    @Override
    public CredentialModel createCredential(RealmModel realm, UserModel user, PhoneOtpCredentialModel credential) {
        if (credential.getCreatedDate() == null) {
            credential.setCreatedDate(Time.currentTimeMillis());
        }
        return getCredentialStore(user).createStoredCredential(credential);
    }

    @Override
    public boolean deleteCredential(RealmModel realm, UserModel user, String credentialId) {
        return getCredentialStore(user).removeStoredCredentialById(credentialId);
    }

    @Override
    public PhoneOtpCredentialModel getCredentialFromModel(CredentialModel credentialModel) {
        return PhoneOtpCredentialModel.createFromCredentialModel(credentialModel);
    }

    @Override
    public CredentialTypeMetadata getCredentialTypeMetadata(CredentialTypeMetadataContext credentialTypeMetadataContext) {
        return CredentialTypeMetadata.builder()
                .type(getType())
                .helpText("")
                .category(CredentialTypeMetadata.Category.TWO_FACTOR)
                .displayName(PhoneOtpCredentialProviderFactory.PROVIDER_ID)
                .createAction(SmsOtpMfaAuthenticatorFactory.PROVIDER_ID)
                .removeable(true)
                .build(session);
    }
}
