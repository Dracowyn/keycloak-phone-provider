package cc.coopersoft.keycloak.phone.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for {@link StringUtils}.
 */
class StringUtilsTest {

    @Test
    @DisplayName("isBlank_matchesNullEmptyAndWhitespaceOnly")
    void isBlank_matchesNullEmptyAndWhitespaceOnly() {
        assertThat(StringUtils.isBlank(null)).isTrue();
        assertThat(StringUtils.isBlank("")).isTrue();
        assertThat(StringUtils.isBlank("   ")).isTrue();
        assertThat(StringUtils.isBlank(" \t\r\n ")).isTrue();

        assertThat(StringUtils.isBlank("x")).isFalse();
        assertThat(StringUtils.isBlank(" x ")).isFalse();
        assertThat(StringUtils.isBlank("0")).isFalse();
    }
}
