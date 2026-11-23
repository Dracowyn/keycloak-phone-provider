package cc.coopersoft.keycloak.phone.utils;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.UserProfileProvider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 检查 realm 的 User Profile 配置是否允许用户自己修改 {@code phoneNumber} / {@code phoneNumberVerified}。
 * <p>
 * 插件只看 {@code phoneNumberVerified=true}，不核对它当初验证的是哪个号码。用户能自己改这两个属性时，
 * 可以把号码换成别人的且仍保持「已验证」，按号码登录、重置密码、直授就会落到这个账户上。
 * <p>
 * {@link UserProfileProvider} 是 Keycloak 的私有 API，插件只在这里调用它；读不到配置时安静跳过。
 * 每个 realm 在进程内只检查一次，配置改动后要重启才会重新检查。
 */
public final class UserProfileRiskCheck {

    private static final Logger logger = Logger.getLogger(UserProfileRiskCheck.class);

    /**
     * User Profile 权限里代表最终用户本人的角色名。
     */
    private static final String ROLE_USER = "user";

    private static final Set<String> CHECKED_REALMS = ConcurrentHashMap.newKeySet();

    private UserProfileRiskCheck() {
    }

    /**
     * 对当前 realm 做一次检查，发现风险时打 ERROR。同一 realm 之后的调用直接返回。
     *
     * @param session Keycloak 会话
     * @param realm   当前 realm，为 {@code null} 时跳过
     */
    public static void checkOnce(KeycloakSession session, RealmModel realm) {
        if (realm == null || !CHECKED_REALMS.add(realm.getId())) {
            return;
        }
        UPConfig config;
        try {
            UserProfileProvider provider = session.getProvider(UserProfileProvider.class);
            config = provider == null ? null : provider.getConfiguration();
        } catch (RuntimeException | LinkageError e) {
            logger.debugf(e, "读取 realm %s 的 User Profile 配置失败，跳过号码属性风险检查", realm.getName());
            return;
        }
        if (config == null) {
            return;
        }
        List<String> editable = userEditablePhoneAttributes(config);
        if (!editable.isEmpty()) {
            logger.errorf("realm %s 的 User Profile 允许用户自行修改 %s：插件只认 phoneNumberVerified=true，"
                    + "不核对它验证的是哪个号码，用户改号后仍算已验证，按号码登录、重置密码可能落到这个账户上。"
                    + "请在 User Profile 中声明 phoneNumber 和 phoneNumberVerified，编辑权限只给 admin", realm.getName(), editable);
        }
    }

    /**
     * 列出用户本人可编辑的号码相关属性。
     *
     * @param config realm 的 User Profile 配置
     * @return 用户可编辑的属性名，没有风险时为空列表
     */
    static List<String> userEditablePhoneAttributes(UPConfig config) {
        return Stream.of(PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER,
                        PhoneConstants.USER_ATTRIBUTE_FIELD_PHONE_NUMBER_VERIFIED)
                .filter(name -> isUserEditable(config, name))
                .toList();
    }

    private static boolean isUserEditable(UPConfig config, String attributeName) {
        // getAttribute 在属性列表为 null 时会 NPE
        UPAttribute attribute = config.getAttributes() == null ? null : config.getAttribute(attributeName);
        if (attribute == null) {
            // 未声明的属性归未托管属性策略管：ENABLED 时用户可以在账户控制台里自行编辑
            return config.getUnmanagedAttributePolicy() == UPConfig.UnmanagedAttributePolicy.ENABLED;
        }
        UPAttributePermissions permissions = attribute.getPermissions();
        return permissions != null && permissions.getEdit() != null && permissions.getEdit().contains(ROLE_USER);
    }
}
