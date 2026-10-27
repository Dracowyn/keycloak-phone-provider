package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.keycloak.util.JsonSerialization;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for the SMS OTP "remember me" cookie.
 */
class SmsOtpMfaAuthenticatorTest {

    private static final String ISSUER = "https://sso.example.com/realms/demo";
    private static final String USER_ID = "user-a";
    private static final String CREDENTIAL_ID = "cred-a";
    private static final long NOW = 1_800_000_000L;

    @Nested
    @DisplayName("SmsOtpRememberToken.matches")
    class Matches {

        private final SmsOtpRememberToken token = new SmsOtpRememberToken(ISSUER, USER_ID, CREDENTIAL_ID, NOW, 3600);

        @Test
        @DisplayName("matches_sameRealmUserCredentialWithinLifetime_returnsTrue")
        void matches_sameRealmUserCredentialWithinLifetime_returnsTrue() {
            assertThat(token.matches(ISSUER, USER_ID, CREDENTIAL_ID, NOW + 10)).isTrue();
        }

        @Test
        @DisplayName("matches_otherUser_returnsFalse")
        void matches_otherUser_returnsFalse() {
            assertThat(token.matches(ISSUER, "user-b", CREDENTIAL_ID, NOW + 10)).isFalse();
        }

        @Test
        @DisplayName("matches_otherRealm_returnsFalse")
        void matches_otherRealm_returnsFalse() {
            assertThat(token.matches("https://sso.example.com/realms/other", USER_ID, CREDENTIAL_ID, NOW + 10)).isFalse();
        }

        @Test
        @DisplayName("matches_otherCredential_returnsFalse")
        void matches_otherCredential_returnsFalse() {
            assertThat(token.matches(ISSUER, USER_ID, "cred-b", NOW + 10)).isFalse();
        }

        @Test
        @DisplayName("matches_expired_returnsFalse")
        void matches_expired_returnsFalse() {
            assertThat(token.matches(ISSUER, USER_ID, CREDENTIAL_ID, NOW + 3600)).isFalse();
        }

        @Test
        @DisplayName("matches_wrongTokenType_returnsFalse")
        void matches_wrongTokenType_returnsFalse() {
            token.type("Bearer");
            assertThat(token.matches(ISSUER, USER_ID, CREDENTIAL_ID, NOW + 10)).isFalse();
        }

        @Test
        @DisplayName("matches_emptyToken_returnsFalse")
        void matches_emptyToken_returnsFalse() {
            assertThat(new SmsOtpRememberToken().matches(ISSUER, USER_ID, CREDENTIAL_ID, NOW)).isFalse();
        }

        @Test
        @DisplayName("matches_jsonRoundTrip_keepsCredentialBinding")
        void matches_jsonRoundTrip_keepsCredentialBinding() throws Exception {
            String json = JsonSerialization.writeValueAsString(token);
            SmsOtpRememberToken decoded = JsonSerialization.readValue(json, SmsOtpRememberToken.class);

            assertThat(decoded.getCredentialId()).isEqualTo(CREDENTIAL_ID);
            assertThat(decoded.matches(ISSUER, USER_ID, CREDENTIAL_ID, NOW + 10)).isTrue();
        }
    }

    @Nested
    @DisplayName("buildCookieHeader")
    class BuildCookieHeader {

        @Test
        @DisplayName("buildCookieHeader_https_addsSecureHttpOnlySameSite")
        void buildCookieHeader_https_addsSecureHttpOnlySameSite() {
            String header = SmsOtpMfaAuthenticator.buildCookieHeader("SMS_OTP_ANSWERED", "a.b.c", "/realms/demo", 600, true);
            assertThat(header).isEqualTo(
                    "SMS_OTP_ANSWERED=a.b.c; Path=/realms/demo; Max-Age=600; Secure; HttpOnly; SameSite=Lax");
        }

        @Test
        @DisplayName("buildCookieHeader_http_omitsSecure")
        void buildCookieHeader_http_omitsSecure() {
            String header = SmsOtpMfaAuthenticator.buildCookieHeader("SMS_OTP_ANSWERED", "a.b.c", "/realms/demo", 600, false);
            assertThat(header).doesNotContain("Secure").contains("HttpOnly");
        }
    }

    @Nested
    @DisplayName("parseCookieMaxAge")
    class ParseCookieMaxAge {

        @Test
        @DisplayName("parseCookieMaxAge_validNumber_returnsIt")
        void parseCookieMaxAge_validNumber_returnsIt() {
            assertThat(SmsOtpMfaAuthenticator.parseCookieMaxAge(" 120 ")).isEqualTo(120);
        }

        @Test
        @DisplayName("parseCookieMaxAge_missingInvalidOrNonPositive_returnsDefault")
        void parseCookieMaxAge_missingInvalidOrNonPositive_returnsDefault() {
            int fallback = SmsOtpMfaAuthenticator.DEFAULT_COOKIE_MAX_AGE;
            assertThat(SmsOtpMfaAuthenticator.parseCookieMaxAge(null)).isEqualTo(fallback);
            assertThat(SmsOtpMfaAuthenticator.parseCookieMaxAge("")).isEqualTo(fallback);
            assertThat(SmsOtpMfaAuthenticator.parseCookieMaxAge("abc")).isEqualTo(fallback);
            assertThat(SmsOtpMfaAuthenticator.parseCookieMaxAge("0")).isEqualTo(fallback);
            assertThat(SmsOtpMfaAuthenticator.parseCookieMaxAge("-5")).isEqualTo(fallback);
        }
    }
}
