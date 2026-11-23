package cc.coopersoft.keycloak.phone.utils;

import org.jboss.logging.Logger;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.util.List;
import java.util.stream.Stream;

/**
 * 按手机号查找账户的工具方法。
 */
public class UserUtils {

    private static final Logger logger = Logger.getLogger(UserUtils.class);

    /**
     * 日志里只保留号码末尾的位数，不打完整号码。
     */
    private static final int LOGGED_PHONE_TAIL_LENGTH = 4;

    private static boolean isPhoneNumberVerified(UserModel user) {
        return user.getAttributeStream(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER_VERIFIED)
                .anyMatch("true"::equals);
    }

    private static Stream<UserModel> searchByPhone(UserProvider userProvider, RealmModel realm,
                                                   PhoneNumber phoneNumber) {
        return userProvider.searchForUserByUserAttributeStream(
                realm, PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER, phoneNumber.getFullPhoneNumber());
    }

    /**
     * 从同号码的账户里选出唯一的已验证账户。
     * <p>
     * 有多个已验证账户时返回 {@code null} 并打 ERROR：底层查询没有排序，随便取一个会让登录、重置密码、
     * 直授落到不确定的账户上。号码验证通过时会取消同号码其他账户的验证标记，正常数据不会走到这里。
     */
    private static UserModel singleVerifiedUser(Stream<UserModel> users, PhoneNumber phoneNumber) {
        List<UserModel> verified = users.filter(UserUtils::isPhoneNumberVerified).toList();
        if (verified.size() > 1) {
            logger.errorf("尾号 %s 的号码有 %d 个已验证账户 %s，拒绝按号码选择账户；请清理重复的 phoneNumberVerified",
                    phoneTail(phoneNumber), verified.size(), verified.stream().map(UserModel::getId).toList());
            return null;
        }
        return verified.isEmpty() ? null : verified.get(0);
    }

    private static String phoneTail(PhoneNumber phoneNumber) {
        String number = String.valueOf(phoneNumber.getPhoneNumber());
        return number.length() <= LOGGED_PHONE_TAIL_LENGTH ? number
                : number.substring(number.length() - LOGGED_PHONE_TAIL_LENGTH);
    }

    /**
     * 按号码查找唯一的已验证账户。
     *
     * @return 唯一的已验证账户；没有，或有多个已验证账户时返回 {@code null}
     */
    public static UserModel findUserByPhone(UserProvider userProvider, RealmModel realm, PhoneNumber phoneNumber){
        return singleVerifiedUser(searchByPhone(userProvider, realm, phoneNumber), phoneNumber);
    }

    public static UserModel findUserByPhone(UserProvider userProvider, RealmModel realm,
                                            PhoneNumber phoneNumber, String notIs){
        List<UserModel> users = searchByPhone(userProvider, realm, phoneNumber)
                .filter(u -> !u.getId().equals(notIs))
                .toList();
        // 只有一个同号码账户时沿用旧行为直接返回（不要求已验证）；多个时只认唯一的已验证账户
        return users.size() == 1 ? users.get(0) : singleVerifiedUser(users.stream(), phoneNumber);
    }

    /**
     * 号码是否已被账户验证占用，有一个或多个已验证账户都算。
     * <p>
     * 判断「号码已存在」要用这个方法，不能用 {@code findUserByPhone(...) != null}：后者在有多个已验证账户时
     * 返回 {@code null}，注册和自动开户会把它当成号码空闲，新账户随后会把号码抢过去。
     */
    public static boolean isPhoneNumberInUse(UserProvider userProvider, RealmModel realm, PhoneNumber phoneNumber) {
        return searchByPhone(userProvider, realm, phoneNumber).anyMatch(UserUtils::isPhoneNumberVerified);
    }
}
