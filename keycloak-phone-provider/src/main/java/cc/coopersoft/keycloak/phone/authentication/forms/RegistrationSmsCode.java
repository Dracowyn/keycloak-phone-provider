package cc.coopersoft.keycloak.phone.authentication.forms;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneConstants;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.validation.Validation;

/**
 * 注册表单的短信验证码两阶段处理：validate 阶段只校验不消费，success 阶段按 ID 消费。
 * <p>
 * 分两步是为了表单其他字段（邮箱、密码等）校验失败时验证码仍然有效，用户改完表单无需重新收码；
 * 校验通过后必须在 success 阶段消费，否则同一验证码可被重放。
 */
final class RegistrationSmsCode {

    private static final Logger logger = Logger.getLogger(RegistrationSmsCode.class);

    private RegistrationSmsCode() {
    }

    /**
     * validate 阶段：校验注册验证码但不消费（常量时间比较 + 失败计数由 {@link TokenCodeService#checkCode} 完成）。
     * 通过时把验证码 ID 记入会话属性，供 {@link #consume} 使用。
     *
     * @param session          Keycloak 会话
     * @param phoneNumber      注册手机号
     * @param verificationCode 用户提交的验证码，空白时直接判不通过
     * @return 是否通过
     */
    static boolean check(KeycloakSession session, PhoneNumber phoneNumber, String verificationCode) {
        if (Validation.isBlank(verificationCode)) {
            return false;
        }
        TokenCodeRepresentation tokenCode = session.getProvider(TokenCodeService.class)
                .checkCode(phoneNumber, verificationCode, TokenCodeType.REGISTRATION);
        if (tokenCode == null) {
            return false;
        }
        session.setAttribute(PhoneConstants.FIELD_TOKEN_ID, tokenCode.getId());
        return true;
    }

    /**
     * success 阶段：按 validate 阶段记下的 ID 消费验证码，并把手机号标记为该用户已验证。
     *
     * @param session     Keycloak 会话
     * @param user        新注册的用户
     * @param phoneNumber 注册手机号
     */
    static void consume(KeycloakSession session, UserModel user, PhoneNumber phoneNumber) {
        String tokenId = session.getAttribute(PhoneConstants.FIELD_TOKEN_ID, String.class);
        logger.info(String.format("registration user %s phone success, tokenId is: %s", user.getId(), tokenId));
        session.getProvider(TokenCodeService.class).tokenValidated(user, phoneNumber, tokenId);
    }
}
