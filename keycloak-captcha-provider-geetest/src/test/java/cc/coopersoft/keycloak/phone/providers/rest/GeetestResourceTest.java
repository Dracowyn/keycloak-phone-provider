package cc.coopersoft.keycloak.phone.providers.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic tests for {@link GeetestResource#mapFrontendConfig}.
 */
class GeetestResourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest(name = "mapFrontendConfig_v3_success{0}_keepsInitFields")
    @CsvSource({"1", "0"})
    @DisplayName("mapFrontendConfig_v3_keepsInitFieldsAndUnifiedFields")
    void mapFrontendConfig_v3_keepsInitFieldsAndUnifiedFields(int success) {
        JsonNode input = JSON.createObjectNode()
                .put("success", success).put("gt", "geetest-id")
                .put("challenge", "0123456789abcdef0123456789abcdef").put("new_captcha", true);

        JsonNode actual = JSON.valueToTree(GeetestResource.mapFrontendConfig(input));

        assertThat(actual.get("success").isInt()).isTrue();
        assertThat(actual.get("success").asInt()).isEqualTo(success);
        assertThat(actual.get("gt").asText()).isEqualTo("geetest-id");
        assertThat(actual.get("challenge").asText()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(actual.get("new_captcha").isBoolean()).isTrue();
        assertThat(actual.get("type").asText()).isEqualTo("geetest");
        assertThat(actual.get("geetestId").asText()).isEqualTo("geetest-id");
        assertThat(actual.get("captchaAppId").asText()).isEqualTo("geetest-id");
        assertThat(input.has("type")).as("不修改入参").isFalse();
    }

    @Test
    @DisplayName("mapFrontendConfig_v4_keepsExistingResponse")
    void mapFrontendConfig_v4_keepsExistingResponse() throws Exception {
        JsonNode input = JSON.readTree("{\"success\":1,\"gt\":\"v4-id\"}");

        JsonNode actual = JSON.valueToTree(GeetestResource.mapFrontendConfig(input));

        assertThat(actual).isEqualTo(JSON.readTree(
                "{\"type\":\"geetest\",\"geetestId\":\"v4-id\",\"captchaAppId\":\"v4-id\"}"));
    }
}
