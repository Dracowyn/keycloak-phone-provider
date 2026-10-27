package cc.coopersoft.keycloak.phone.authentication.requiredactions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for {@link VerifyPhoneOrEmail#isPhoneNumberVerified}.
 */
class VerifyPhoneOrEmailTest {

    @Test
    @DisplayName("isPhoneNumberVerified_numberAndVerifiedFlag_returnsTrue")
    void isPhoneNumberVerified_numberAndVerifiedFlag_returnsTrue() {
        assertThat(VerifyPhoneOrEmail.isPhoneNumberVerified("+8613800000000", "true")).isTrue();
    }

    @Test
    @DisplayName("isPhoneNumberVerified_numberUnverifiedByOtherAccount_returnsFalse")
    void isPhoneNumberVerified_numberUnverifiedByOtherAccount_returnsFalse() {
        assertThat(VerifyPhoneOrEmail.isPhoneNumberVerified("+8613800000000", "false")).isFalse();
    }

    @Test
    @DisplayName("isPhoneNumberVerified_numberWithoutFlag_returnsFalse")
    void isPhoneNumberVerified_numberWithoutFlag_returnsFalse() {
        assertThat(VerifyPhoneOrEmail.isPhoneNumberVerified("+8613800000000", null)).isFalse();
    }

    @Test
    @DisplayName("isPhoneNumberVerified_blankNumber_returnsFalse")
    void isPhoneNumberVerified_blankNumber_returnsFalse() {
        assertThat(VerifyPhoneOrEmail.isPhoneNumberVerified(null, "true")).isFalse();
        assertThat(VerifyPhoneOrEmail.isPhoneNumberVerified("  ", "true")).isFalse();
    }
}
