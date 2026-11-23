package cc.coopersoft.keycloak.phone.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UserUtilsTest {

    private static final PhoneNumber PHONE = new PhoneNumber("86", "13900000001");

    @Test
    @DisplayName("同号码有多个已验证账户时不随机取一个：按号码找账户返回 null，号码仍算已占用")
    void multipleVerifiedUsers_findReturnsNull_numberStillInUse() {
        UserModel verifiedA = user("a", "true");
        UserModel verifiedB = user("b", "true");
        UserModel unverified = user("c", "false");

        assertThat(UserUtils.findUserByPhone(users(verifiedA, verifiedB, unverified), null, PHONE)).isNull();
        assertThat(UserUtils.isPhoneNumberInUse(users(verifiedA, verifiedB, unverified), null, PHONE)).isTrue();
        assertThat(UserUtils.findUserByPhone(users(verifiedA, unverified), null, PHONE)).isSameAs(verifiedA);
    }

    /** 按号码查询时返回给定账户的 UserProvider 桩。 */
    private static UserProvider users(UserModel... users) {
        return (UserProvider) Proxy.newProxyInstance(UserProvider.class.getClassLoader(),
                new Class<?>[]{UserProvider.class}, (proxy, method, args) -> {
                    if ("searchForUserByUserAttributeStream".equals(method.getName())) {
                        return Stream.of(users);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static UserModel user(String id, String phoneNumberVerified) {
        return (UserModel) Proxy.newProxyInstance(UserModel.class.getClassLoader(),
                new Class<?>[]{UserModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getAttributeStream" -> PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER_VERIFIED.equals(args[0])
                            ? Stream.of(phoneNumberVerified) : Stream.empty();
                    case "toString" -> "user-" + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
