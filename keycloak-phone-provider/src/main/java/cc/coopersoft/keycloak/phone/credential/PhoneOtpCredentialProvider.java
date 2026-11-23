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
import org.keycloak.util.JsonSerialization;

import java.io.IOException;

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
        if (!(input instanceof UserCredentialModel) || !getType().equals(input.getType())) {
            return false;
        }
        String code = input.getChallengeResponse();
        if (code == null) {
            return false;
        }

        String credentialId = input.getCredentialId();
        CredentialModel credential = credentialId == null
                ? getCredentialStore(user).getStoredCredentialsByTypeStream(getType()).findFirst().orElse(null)
                : getCredentialStore(user).getStoredCredentialById(credentialId);
        if (credentialId != null && (credential == null || !getType().equals(credential.getType()))) {
            return false;
        }

        PhoneNumber phoneNumber = getMfaPhoneNumber(user, credential);
        if (phoneNumber.isEmpty()) {
            return false;
        }
        // 号码取自凭据，不再要求与 phoneNumber 属性一致：属性可能已被改成别的号码
        return getTokenCodeService().validateCode(phoneNumber, code, TokenCodeType.OTP);
    }

    /**
     * 短信 MFA 发码和校验用的号码：取凭据里登记的号码，不读 {@code phoneNumber} 用户属性。
     * <p>
     * 属性可能经 User Profile、管理端被改掉而不经过短信验证，按属性发码会让第二因子跟着改过的号码走。
     * 凭据没有号码数据（旧数据）或不存在时回退到用户属性，并打 WARN；用户重新验证号码后凭据会补上号码。
     *
     * @param user       用户
     * @param credential 短信 OTP 凭据，可为 {@code null}
     * @return 号码；属性也没有时返回空号码（{@link PhoneNumber#isEmpty()} 为 {@code true}）
     */
    public PhoneNumber getMfaPhoneNumber(UserModel user, CredentialModel credential) {
        PhoneNumber registered = registeredPhoneNumber(credential);
        if (registered != null) {
            return registered;
        }
        logger.warnf("用户 %s 的短信 OTP 凭据%s，回退到 phoneNumber 属性", user.getId(),
                credential == null ? "不存在" : " " + credential.getId() + " 没有登记号码（旧数据）");
        String attribute = user.getFirstAttribute(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER);
        return attribute == null ? new PhoneNumber(null, null) : new PhoneNumber(attribute);
    }

    /**
     * 读出凭据数据里登记的号码。
     *
     * @return 登记的号码；凭据为空、没有号码数据或数据无法解析时返回 {@code null}
     */
    static PhoneNumber registeredPhoneNumber(CredentialModel credential) {
        if (credential == null || credential.getCredentialData() == null) {
            return null;
        }
        try {
            PhoneOtpCredentialModel.SmsOtpCredentialData data = JsonSerialization.readValue(
                    credential.getCredentialData(), PhoneOtpCredentialModel.SmsOtpCredentialData.class);
            PhoneNumber phoneNumber = new PhoneNumber(data.getAreaCode(), data.getPhoneNumber());
            return phoneNumber.isEmpty() ? null : phoneNumber;
        } catch (IOException e) {
            logger.debugf(e, "无法解析短信 OTP 凭据 %s 的号码数据", credential.getId());
            return null;
        }
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
