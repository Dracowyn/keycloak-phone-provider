package cc.coopersoft.keycloak.phone.providers.constants;

import lombok.Getter;

@Getter
public enum TokenCodeType {
    VERIFY("verification"),
    OTP("authentication"),
    RESET("reset credential"),
    REGISTRATION("registration"),
    LOGIN("login");

    private final String label;

    TokenCodeType(String label) {
        this.label  = label;
    }
}
