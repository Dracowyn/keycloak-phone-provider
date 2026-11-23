package cc.coopersoft.keycloak.phone.providers.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for {@link MessageSendResult}.
 */
class MessageSendResultTest {

    @Test
    @DisplayName("ok_positiveStatus_returnsTrue")
    void ok_positiveStatus_returnsTrue() {
        assertThat(new MessageSendResult(1).ok()).isTrue();
        assertThat(MessageSendResult.success().ok()).isTrue();
    }

    @Test
    @DisplayName("ok_zeroOrNegativeStatus_returnsFalse")
    void ok_zeroOrNegativeStatus_returnsFalse() {
        assertThat(new MessageSendResult(0).ok()).isFalse();
        assertThat(new MessageSendResult(-1).ok()).isFalse();
        assertThat(MessageSendResult.failure("SOME_CODE", "some message").ok()).isFalse();
        assertThat(MessageSendResult.failure(ErrorCode.SMS_SEND_FAILED).ok()).isFalse();
    }

    @Test
    @DisplayName("intConstructor_mapsLegacyStatusToEnum")
    void intConstructor_mapsLegacyStatusToEnum() {
        MessageSendResult positive = new MessageSendResult(7);
        assertThat(positive.ok()).isTrue();
        assertThat(positive.isResendCooldown()).isFalse();
        assertThat(positive.getStatus()).isEqualTo(7);

        MessageSendResult zero = new MessageSendResult(0);
        assertThat(zero.ok()).isFalse();
        assertThat(zero.isResendCooldown()).isFalse();
        assertThat(zero.getStatus()).isZero();

        MessageSendResult failure = new MessageSendResult(-1);
        assertThat(failure.ok()).isFalse();
        assertThat(failure.isResendCooldown()).isFalse();
        assertThat(failure.getStatus()).isEqualTo(-1);

        MessageSendResult cooldown = new MessageSendResult(-2);
        assertThat(cooldown.ok()).isFalse();
        assertThat(cooldown.isResendCooldown()).isTrue();
        assertThat(cooldown.getStatus()).isEqualTo(-2);

        MessageSendResult factory = MessageSendResult.resendCooldown(
                MessageSendResult.ERROR_CODE_RATE_TIME, "Please wait for minutes.");
        assertThat(factory.isResendCooldown()).isTrue();
        assertThat(factory.getStatus()).isEqualTo(-2);
        assertThat(factory.getErrorCode()).isEqualTo(MessageSendResult.ERROR_CODE_RATE_TIME);
    }

    @Test
    @DisplayName("getResendExpiresTime_resendExpiresNull_returnsZero")
    void getResendExpiresTime_resendExpiresNull_returnsZero() {
        MessageSendResult result = new MessageSendResult(1);

        assertThat(result.getResendExpires()).isNull();
        assertThat(result.getResendExpiresTime()).isZero();
    }

    @Test
    @DisplayName("setError_chainedCall_returnsSameInstanceAndSetsFields")
    void setError_chainedCall_returnsSameInstanceAndSetsFields() {
        MessageSendResult result = new MessageSendResult(-1);

        MessageSendResult chained = result.setError("INVALID_REQUEST", "请求格式错误");

        assertThat(chained).isSameAs(result);
        assertThat(result.getErrorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(result.getErrorMessage()).isEqualTo("请求格式错误");
    }
}
