package cc.coopersoft.keycloak.phone.providers.sender;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for Aliyun response parsing and phone number formatting.
 */
class AliyunSmsSenderServiceProviderImplTest {

    @Test
    @DisplayName("parseResponse_codeOk_succeedsWithExpiryAndCooldown")
    void parseResponse_codeOk_succeedsWithExpiryAndCooldown() {
        LocalDateTime before = LocalDateTime.now();
        MessageSendResult result = AliyunSmsSenderServiceProviderImpl.parseResponse("{\"Code\":\"OK\",\"Message\":\"OK\"}", 300);
        LocalDateTime after = LocalDateTime.now();

        assertThat(result.ok()).isTrue();
        assertThat(result.getExpires()).isBetween(before.plusSeconds(300), after.plusSeconds(300));
        assertThat(result.getResendExpires()).isBetween(before.plusSeconds(60), after.plusSeconds(60));
    }

    @ParameterizedTest(name = "parseResponse_businessError_{0}")
    @CsvSource({"isv.BUSINESS_LIMIT_CONTROL,业务限流", "isv.AMOUNT_NOT_ENOUGH,余额不足", "ok,大小写不匹配"})
    @DisplayName("parseResponse_businessError_keepsAliyunCodeAndMessage")
    void parseResponse_businessError_keepsAliyunCodeAndMessage(String code, String message) {
        MessageSendResult result = AliyunSmsSenderServiceProviderImpl.parseResponse(
                "{\"Code\":\"" + code + "\",\"Message\":\"" + message + "\"}", 300);

        assertThat(result.ok()).isFalse();
        assertThat(result.getStatus()).isEqualTo(-1);
        assertThat(result.getErrorCode()).isEqualTo(code);
        assertThat(result.getErrorMessage()).isEqualTo(message);
        assertThat(result.getExpires()).isNull();
    }

    @ParameterizedTest(name = "parseResponse_malformed_[{index}]")
    @NullAndEmptySource
    @ValueSource(strings = {"null", "[]", "{}", "{\"Code\":null}", "{\"Code\":1}", "{", "{\"Code\":\"OK\"} {}"})
    @DisplayName("parseResponse_malformed_isFailure")
    void parseResponse_malformed_isFailure(String data) {
        MessageSendResult result = AliyunSmsSenderServiceProviderImpl.parseResponse(data, 300);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("ALIYUN_RESPONSE_PARSE_ERROR");
        assertThat(result.getExpires()).isNull();
    }

    @ParameterizedTest(name = "formatPhoneNumber_{0}_{1}")
    @CsvSource({"86,13800000000,13800000000", "852,51234567,85251234567", "1,2025550123,12025550123"})
    @DisplayName("formatPhoneNumber_keepsInternationalAreaCodeWithoutPrefix")
    void formatPhoneNumber_keepsInternationalAreaCodeWithoutPrefix(String areaCode, String number, String expected) {
        assertThat(AliyunSmsSenderServiceProviderImpl.formatPhoneNumber(new PhoneNumber(areaCode, number)))
                .isEqualTo(expected);
    }
}
