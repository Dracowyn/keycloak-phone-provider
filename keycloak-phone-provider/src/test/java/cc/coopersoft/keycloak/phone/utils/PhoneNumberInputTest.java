package cc.coopersoft.keycloak.phone.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumberInputTest {

    @Test
    @DisplayName("请求号码按规则表校验：区号允许 + 和前导 0 并规范化，号码只收纯数字；逗号列表、重复区号前缀等变体一律拒绝")
    void normalize_followsRuleTable() {
        // 合格输入 -> 规范化后的「区号 号码」
        Map<String[], String> valid = Map.ofEntries(
                Map.entry(new String[]{"86", "13900000001"}, "86 13900000001"),
                Map.entry(new String[]{" +86 ", "\t13900000001 "}, "86 13900000001"),
                Map.entry(new String[]{"086", "13900000001"}, "86 13900000001"),
                Map.entry(new String[]{"0086", "13900000001"}, "86 13900000001"),
                Map.entry(new String[]{"+0086", "13900000001"}, "86 13900000001"),
                Map.entry(new String[]{"1", "2025550123"}, "1 2025550123"),
                Map.entry(new String[]{"852", "51234567"}, "852 51234567"),
                Map.entry(new String[]{"44", "12345"}, "44 12345"),
                // 区号加号码恰好 15 位
                Map.entry(new String[]{"1", "12345678901234"}, "1 12345678901234"));
        valid.forEach((input, expected) -> {
            Optional<PhoneNumber> result = PhoneNumberInput.normalize(input[0], input[1]);
            assertThat(result).as("%s / %s 应合格", input[0], input[1]).isPresent();
            assertThat(result.get().getAreaCode() + " " + result.get().getPhoneNumber()).isEqualTo(expected);
        });

        String[][] invalid = {
                {"86", "13900000070,13900000071"},
                {"86", "+8613900000050"},
                {"86", "8613900000050"},
                {"86", "139 0000 0001"},
                {"86", "1390000000\n1"},
                {"86", "1390000000"},
                {"86", "23900000001"},
                {"86", "１３９００００００01"},
                {"0", "2025550123"},
                {"000", "2025550123"},
                {"1234", "12345"},
                {"++86", "13900000001"},
                {"8a", "13900000001"},
                {"+", "13900000001"},
                {"1", "1234"},
                {"1", "1234567890123456"},
                {"44", "12345678901234"},
                {null, "13900000001"},
                {"86", null},
                {"86", " "},
        };
        for (String[] input : invalid) {
            assertThat(PhoneNumberInput.normalize(input[0], input[1]))
                    .as("%s / %s 应拒绝", input[0], input[1]).isEmpty();
        }
    }
}
