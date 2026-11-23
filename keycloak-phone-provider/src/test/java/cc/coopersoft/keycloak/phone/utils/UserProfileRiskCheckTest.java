package cc.coopersoft.keycloak.phone.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileRiskCheckTest {

    @Test
    @DisplayName("号码属性对 user 可编辑，或未声明且未托管属性策略为 ENABLED 时报风险；只给 admin 编辑则不报")
    void userEditablePhoneAttributes_flagsUserEditableAndUndeclaredUnderEnabledPolicy() {
        UPConfig undeclared = new UPConfig();
        undeclared.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        assertThat(UserProfileRiskCheck.userEditablePhoneAttributes(undeclared))
                .containsExactly("phoneNumber", "phoneNumberVerified");

        UPConfig declared = new UPConfig();
        declared.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        declared.addOrReplaceAttribute(new UPAttribute("phoneNumber",
                new UPAttributePermissions(Set.of("admin", "user"), Set.of("admin", "user"))));
        declared.addOrReplaceAttribute(new UPAttribute("phoneNumberVerified",
                new UPAttributePermissions(Set.of("admin"), Set.of("admin"))));
        assertThat(UserProfileRiskCheck.userEditablePhoneAttributes(declared)).containsExactly("phoneNumber");

        UPConfig adminViewOnly = new UPConfig();
        adminViewOnly.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ADMIN_VIEW);
        assertThat(UserProfileRiskCheck.userEditablePhoneAttributes(adminViewOnly)).isEmpty();
    }
}
