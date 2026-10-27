package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.keycloak.phone.authentication.requiredactions.UpdatePhoneNumberRequiredAction;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialModel;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProvider;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProviderFactory;
import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.jpa.TokenCodeEntity;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class TokenCodeServiceImpl implements TokenCodeService {

    private static final Logger logger = Logger.getLogger(TokenCodeServiceImpl.class);

    /**
     * 单个验证码允许的最大校验失败次数，达到后该验证码即被作废（防爆破）。
     */
    private static final int MAX_VERIFICATION_ATTEMPTS = 5;

    private final KeycloakSession session;

    TokenCodeServiceImpl(KeycloakSession session) {
        this.session = session;
        if (getRealm() == null) {
            throw new IllegalStateException("The service cannot accept a session without a realm in its context.");
        }
    }

    private EntityManager getEntityManager() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    private RealmModel getRealm() {
        return session.getContext().getRealm();
    }

    @Override
    public TokenCodeRepresentation currentProcess(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        TokenCodeEntity entity = currentProcessEntity(phoneNumber, tokenCodeType);
        return entity == null ? null : getTokenCodeRepresentation(entity);
    }

    /**
     * 获取当前有效的验证码托管实体（managed entity）。
     * <p>
     * 命名查询 {@code currentProcess} 已按 {@code createdAt} 降序排序，配合 {@code setMaxResults(1)}
     * 只取最新一条，避免并发双发产生多行有效记录时 {@code getSingleResult()} 抛
     * {@code NonUniqueResultException}。返回的是托管实体，可直接修改（如递增 attempts）后随事务落库。
     *
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     * @return 最新的有效验证码实体，不存在时返回 {@code null}
     */
    private TokenCodeEntity currentProcessEntity(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        return getEntityManager()
                .createNamedQuery("currentProcess", TokenCodeEntity.class)
                .setParameter("realmId", getRealm().getId())
                .setParameter("areaCode", phoneNumber.getAreaCode())
                .setParameter("phoneNumber", phoneNumber.getPhoneNumber())
                .setParameter("now", LocalDateTime.now())
                .setParameter("type", tokenCodeType.name())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private static TokenCodeRepresentation getTokenCodeRepresentation(TokenCodeEntity entity) {
        TokenCodeRepresentation tokenCodeRepresentation = new TokenCodeRepresentation();

        tokenCodeRepresentation.setId(entity.getId());
        tokenCodeRepresentation.setPhoneNumber(entity.getPhoneNumber());
        tokenCodeRepresentation.setCode(entity.getCode());
        tokenCodeRepresentation.setType(entity.getType());
        tokenCodeRepresentation.setCreatedAt(entity.getCreatedAt());
        tokenCodeRepresentation.setExpiresAt(entity.getExpiresAt());
        tokenCodeRepresentation.setResendExpiresAt(entity.getResendExpiresAt());
        tokenCodeRepresentation.setConfirmed(entity.getConfirmed());
        return tokenCodeRepresentation;
    }

    @Override
    public void removeCode(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        try {
            EntityManager em = getEntityManager();
            List<TokenCodeEntity> entityList = em
                    .createNamedQuery("getAll", TokenCodeEntity.class)
                    .setParameter("realmId", getRealm().getId())
                    .setParameter("areaCode", phoneNumber.getAreaCode())
                    .setParameter("phoneNumber", phoneNumber.getPhoneNumber())
                    .setParameter("type", tokenCodeType.name())
                    .getResultList();

            if (!entityList.isEmpty()) {
                for (TokenCodeEntity entity : entityList) {
                    em.remove(entity);
                }
                // 不能 em.clear()：该 EntityManager 由 Keycloak 共享，清空会把同一事务中的用户等实体一并游离，
                // 之后对用户的修改（如注册流程后续表单动作）会静默丢失
                em.flush();
            }
        } catch (NoResultException ignored) {

        }
    }

    /**
     * 获取最近发送的一条记录（不论验证码是否已过期或因输错被作废），用于重发冷却判断。
     * 只看有效验证码的话，输错作废后冷却期会被立即解除。
     */
    private TokenCodeEntity latestProcessEntity(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        return getEntityManager()
                .createNamedQuery("getAll", TokenCodeEntity.class)
                .setParameter("realmId", getRealm().getId())
                .setParameter("areaCode", phoneNumber.getAreaCode())
                .setParameter("phoneNumber", phoneNumber.getPhoneNumber())
                .setParameter("type", tokenCodeType.name())
                .getResultStream()
                .filter(e -> e.getCreatedAt() != null)
                .max(Comparator.comparing(TokenCodeEntity::getCreatedAt))
                .orElse(null);
    }

    @Override
    public boolean canResend(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        TokenCodeEntity entity = latestProcessEntity(phoneNumber, tokenCodeType);
        if (entity == null) {
            return true;
        }
        LocalDateTime resendExpiresAt = entity.getResendExpiresAt();
        return (resendExpiresAt == null || resendExpiresAt.isBefore(LocalDateTime.now()));
    }

    @Override
    public boolean isAbusing(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {

        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

        List<TokenCodeEntity> entities = getEntityManager()
                .createNamedQuery("processesSince", TokenCodeEntity.class)
                .setParameter("realmId", getRealm().getId())
                .setParameter("areaCode", phoneNumber.getAreaCode())
                .setParameter("phoneNumber", phoneNumber.getPhoneNumber())
                .setParameter("date", oneHourAgo)
                .setParameter("type", tokenCodeType.name())
                .getResultList();

        return entities.size() > 3;
    }

    @Override
    public void persistCode(TokenCodeRepresentation tokenCode, TokenCodeType tokenCodeType, MessageSendResult sendResult) {
        // 只清理1小时前的历史记录：最近1小时内的记录必须保留，isAbusing 依赖它们统计发送频次。
        // 若在此处全量删除（或发送前调用 removeCode），表中任意时刻至多1行，一小时防滥用上限将永远不会触发。
        // 校验成功时 removeCode 仍会清空该号码该类型的全部记录，不会长期堆积。
        EntityManager em = getEntityManager();
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        em.createNamedQuery("getAll", TokenCodeEntity.class)
                .setParameter("realmId", getRealm().getId())
                .setParameter("areaCode", tokenCode.getAreaCode())
                .setParameter("phoneNumber", tokenCode.getPhoneNumber())
                .setParameter("type", tokenCodeType.name())
                .getResultList().stream()
                .filter(e -> e.getCreatedAt() == null || e.getCreatedAt().isBefore(cutoff))
                .forEach(em::remove);

        TokenCodeEntity entity = new TokenCodeEntity();
        LocalDateTime now = LocalDateTime.now();

        entity.setId(tokenCode.getId());
        entity.setRealmId(getRealm().getId());
        entity.setAreaCode(tokenCode.getAreaCode());
        entity.setPhoneNumber(tokenCode.getPhoneNumber());
        entity.setCode(tokenCode.getCode());
        entity.setType(tokenCodeType.name());
        entity.setCreatedAt(now);
        entity.setExpiresAt(sendResult.getExpires());
        entity.setResendExpiresAt(sendResult.getResendExpires());
        entity.setConfirmed(tokenCode.getConfirmed());

        getEntityManager().persist(entity);
    }

    /**
     * 常量时间比较验证码，避免因 {@link String#equals} 提前返回而泄露匹配进度的时序侧信道。
     * 任一方为 {@code null} 时直接判定不匹配。
     *
     * @param expected 服务端保存的验证码
     * @param actual   用户提交的验证码
     * @return 两者是否完全一致
     */
    private static boolean codesMatch(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记录一次验证码校验失败：递增 attempts；一旦达到 {@link #MAX_VERIFICATION_ATTEMPTS} 上限，
     * 只把当前这条验证码置为过期使其作废，用户须重新发送。
     * <p>
     * 不能删除该号码的全部记录：最近1小时的记录是 {@link #isAbusing} 与 {@link #canResend} 的频控依据，
     * 删除后攻击者每猜错5次就能换来一轮新的发送额度。
     * <p>
     * 仅应在真正的验证入口（非委托重载）调用，避免重复计数。
     *
     * @param tokenCode 当前验证码托管实体
     */
    private void registerFailedAttempt(TokenCodeEntity tokenCode) {
        int attempts = (tokenCode.getAttempts() == null ? 0 : tokenCode.getAttempts()) + 1;
        tokenCode.setAttempts(attempts);
        if (attempts >= MAX_VERIFICATION_ATTEMPTS) {
            expireActiveCodes(tokenCode);
        }
        getEntityManager().flush();
    }

    /**
     * 作废该号码该类型下所有未过期的验证码（只改过期时间，记录保留给频控）。
     * 只作废当前一条的话，更早发出且未过期的验证码会重新成为 currentProcess，被继续校验。
     */
    private void expireActiveCodes(TokenCodeEntity tokenCode) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expired = now.minusSeconds(1);
        getEntityManager()
                .createNamedQuery("getAll", TokenCodeEntity.class)
                .setParameter("realmId", tokenCode.getRealmId())
                .setParameter("areaCode", tokenCode.getAreaCode())
                .setParameter("phoneNumber", tokenCode.getPhoneNumber())
                .setParameter("type", tokenCode.getType())
                .getResultStream()
                .filter(e -> e.getExpiresAt() != null && !e.getExpiresAt().isBefore(now))
                .forEach(e -> e.setExpiresAt(expired));
        tokenCode.setExpiresAt(expired);
    }

    @Override
    public TokenCodeRepresentation checkCode(PhoneNumber phoneNumber, String code, TokenCodeType tokenCodeType) {
        TokenCodeEntity tokenCode = currentProcessEntity(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            return null;
        }
        if (!codesMatch(tokenCode.getCode(), code)) {
            registerFailedAttempt(tokenCode);
            return null;
        }
        return getTokenCodeRepresentation(tokenCode);
    }

    @Override
    public boolean validateCode(PhoneNumber phoneNumber, String code) {
        return validateCode(phoneNumber, code, TokenCodeType.VERIFY);
    }

    @Override
    public boolean validateCode(PhoneNumber phoneNumber, String code, TokenCodeType tokenCodeType) {
        if (checkCode(phoneNumber, code, tokenCodeType) == null) {
            return false;
        }

        removeCode(phoneNumber, tokenCodeType);
        return true;
    }

    @Override
    public boolean validateCode(UserModel user, PhoneNumber phoneNumber, String code) {
        return validateCode(user, phoneNumber, code, TokenCodeType.VERIFY);
    }

    @Override
    public boolean validateCode(UserModel user, PhoneNumber phoneNumber, String code, TokenCodeType tokenCodeType) {
        TokenCodeEntity tokenCode = currentProcessEntity(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            return false;
        }
        if (!codesMatch(tokenCode.getCode(), code)) {
            registerFailedAttempt(tokenCode);
            return false;
        }
        if (user.getAttributeStream("phoneNumber")
                .noneMatch(p -> p.equals(phoneNumber.getFullPhoneNumber()))) {
            return false;
        }

        removeCode(phoneNumber, tokenCodeType);
        return true;
    }

    @Override
    public void setUserPhoneNumberByCode(UserModel user, PhoneNumber phoneNumber, String code) {
        TokenCodeType tokenCodeType = TokenCodeType.VERIFY;
        logger.info(String.format("valid %s , phone: %s", tokenCodeType, phoneNumber.getFullPhoneNumber()));

        TokenCodeEntity tokenCode = currentProcessEntity(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            throw new BadRequestException(String.format("There is no valid ongoing %s process",
                    tokenCodeType.getLabel()));
        }

        if (!codesMatch(tokenCode.getCode(), code)) {
            registerFailedAttempt(tokenCode);
            throw new ForbiddenException("Code does not match with expected value");
        }

        logger.info(String.format("User %s correctly answered the %s code", user.getId(), tokenCodeType.getLabel()));

        removeCode(phoneNumber, tokenCodeType);
        session.users()
                .searchForUserByUserAttributeStream(session.getContext().getRealm(), "phoneNumber",
                        phoneNumber.getFullPhoneNumber())
                .filter(u -> !u.getId().equals(user.getId()))
                .forEach(u -> {
                    logger.info(String.format("User %s also has phone number %s. Un-verifying.", u.getId(),
                            phoneNumber.getFullPhoneNumber()));
                    u.setSingleAttribute("phoneNumberVerified", "false");
                });

        user.setSingleAttribute("phoneNumberVerified", "true");
        user.setSingleAttribute("phoneNumber", phoneNumber.getFullPhoneNumber());

        cleanUpAction(user);
    }

    @Override
    public void tokenValidated(UserModel user, PhoneNumber phoneNumber, String tokenCodeId) {
        // 消费验证码：通过 checkCode 校验后必须在此作废，否则同一验证码可被重放
        if (tokenCodeId != null) {
            TokenCodeEntity tokenCode = getEntityManager().find(TokenCodeEntity.class, tokenCodeId);
            if (tokenCode != null) {
                removeCode(phoneNumber, TokenCodeType.valueOf(tokenCode.getType()));
            }
        }

        // 解绑重复的手机号：同一号码至多一个已验证账户，否则按号码登录/重置时账户选择不确定
        session.users().searchForUserByUserAttributeStream(session.getContext().getRealm(), "phoneNumber",
                        phoneNumber.getFullPhoneNumber()).filter(u -> !u.getId().equals(user.getId()))
                .forEach(u -> {
                    logger.info(String.format("User %s also has phone number %s. Un-verifying.", u.getId(),
                            phoneNumber.getFullPhoneNumber()));
                    u.setSingleAttribute("phoneNumberVerified", "false");
                });

        user.setSingleAttribute("phoneNumberVerified", "true");
        user.setSingleAttribute("phoneNumber", phoneNumber.getFullPhoneNumber());

        cleanUpAction(user);
    }

    @Override
    public void cleanUpAction(UserModel user) {
        user.removeRequiredAction(UpdatePhoneNumberRequiredAction.PROVIDER_ID);
        PhoneOtpCredentialProvider socp = (PhoneOtpCredentialProvider)
                session.getProvider(CredentialProvider.class, PhoneOtpCredentialProviderFactory.PROVIDER_ID);
        if (socp.isConfiguredFor(getRealm(), user, PhoneOtpCredentialModel.TYPE)) {
            Optional<CredentialModel> credentialOptional = user.credentialManager()
                    .getStoredCredentialsByTypeStream(PhoneOtpCredentialModel.TYPE).findFirst();
            if (credentialOptional.isPresent()) {
                CredentialModel credential = credentialOptional.get();
                PhoneNumber phoneNumber = new PhoneNumber(user.getFirstAttribute("phoneNumber"));
                credential.setCredentialData(PhoneOtpCredentialModel.create(phoneNumber).getCredentialData());
                PhoneOtpCredentialModel credentialModel = PhoneOtpCredentialModel.createFromCredentialModel(credential);
                user.credentialManager().updateStoredCredential(credentialModel);
            }
        }
    }

    @Override
    public LocalDateTime getResendExpires(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        if (this.canResend(phoneNumber, tokenCodeType)) {
            throw new BadRequestException(String.format("Resend timeout in %s process for %s is finished.",
                    tokenCodeType.getLabel(), phoneNumber.getFullPhoneNumber()));
        }

        TokenCodeEntity tokenCode = latestProcessEntity(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            throw new BadRequestException(String.format("There is no valid %s in process for %s",
                    tokenCodeType.getLabel(), phoneNumber.getFullPhoneNumber()));
        }
        return tokenCode.getResendExpiresAt();
    }

    @Override
    public void close() {
    }
}
