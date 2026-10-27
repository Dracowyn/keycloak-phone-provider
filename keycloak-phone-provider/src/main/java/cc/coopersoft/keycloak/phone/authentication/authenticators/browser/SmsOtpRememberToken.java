package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.keycloak.representations.JsonWebToken;

/**
 * 短信 OTP "一段时间内免验证" Cookie 的令牌载荷。
 * <p>
 * 由 Keycloak {@code TokenManager} 以 realm 内部（HMAC）密钥签名，绑定 realm（iss）、用户（sub）
 * 与短信 OTP 凭据（cid）。任一项不匹配、过期或签名无效都视为未通过，必须重新完成短信校验。
 */
public class SmsOtpRememberToken extends JsonWebToken {

    public static final String TOKEN_TYPE = "SMS_OTP_ANSWERED";
    private static final String CLAIM_CREDENTIAL_ID = "cid";

    public SmsOtpRememberToken() {
    }

    public SmsOtpRememberToken(String issuer, String userId, String credentialId, long nowSeconds, int maxAgeSeconds) {
        issuer(issuer);
        subject(userId);
        type(TOKEN_TYPE);
        iat(nowSeconds);
        exp(nowSeconds + maxAgeSeconds);
        setOtherClaims(CLAIM_CREDENTIAL_ID, credentialId);
    }

    @JsonIgnore
    public String getCredentialId() {
        Object value = getOtherClaims().get(CLAIM_CREDENTIAL_ID);
        return value == null ? null : value.toString();
    }

    /**
     * 判断令牌是否属于指定 realm / 用户 / 凭据，且在有效期内。
     *
     * @param issuer       当前 realm 的 issuer
     * @param userId       当前认证用户 ID
     * @param credentialId 当前用户的短信 OTP 凭据 ID
     * @param nowSeconds   当前时间（秒）
     * @return 全部匹配且未过期时返回 {@code true}
     */
    public boolean matches(String issuer, String userId, String credentialId, long nowSeconds) {
        return TOKEN_TYPE.equals(getType())
                && issuer != null && issuer.equals(getIssuer())
                && userId != null && userId.equals(getSubject())
                && credentialId != null && credentialId.equals(getCredentialId())
                && getExp() != null && getExp() > nowSeconds;
    }
}
