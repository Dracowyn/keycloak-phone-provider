package cc.coopersoft.keycloak.phone.providers.spi.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for Geetest v3 offline-challenge bookkeeping.
 */
class GeetestCaptchaServiceImplTest {

    @Test
    @DisplayName("extractChallenge_registerResult_returnsChallenge")
    void extractChallenge_registerResult_returnsChallenge() {
        String data = "{\"success\":0,\"gt\":\"gt-id\",\"challenge\":\"abc123\",\"new_captcha\":true}";
        assertThat(GeetestCaptchaServiceImpl.extractChallenge(data)).isEqualTo("abc123");
    }

    @Test
    @DisplayName("extractChallenge_missingOrEmptyChallenge_returnsNull")
    void extractChallenge_missingOrEmptyChallenge_returnsNull() {
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("{\"success\":0}")).isNull();
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("{\"challenge\":\"\"}")).isNull();
        assertThat(GeetestCaptchaServiceImpl.extractChallenge(null)).isNull();
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("")).isNull();
    }

    @Test
    @DisplayName("extractChallenge_malformedJson_returnsNull")
    void extractChallenge_malformedJson_returnsNull() {
        assertThat(GeetestCaptchaServiceImpl.extractChallenge("not json")).isNull();
    }

    @Test
    @DisplayName("offlineChallengeKey_differentChallenges_produceDistinctKeys")
    void offlineChallengeKey_differentChallenges_produceDistinctKeys() {
        assertThat(GeetestCaptchaServiceImpl.offlineChallengeKey("a"))
                .isNotEqualTo(GeetestCaptchaServiceImpl.offlineChallengeKey("b"))
                .startsWith("geetest-v3-offline:");
    }
}
