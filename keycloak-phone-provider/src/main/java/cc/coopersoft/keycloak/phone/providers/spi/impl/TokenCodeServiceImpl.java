package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.keycloak.phone.authentication.requiredactions.UpdatePhoneNumberRequiredAction;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialModel;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProvider;
import cc.coopersoft.keycloak.phone.credential.PhoneOtpCredentialProviderFactory;
import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.jpa.TokenCodeEntity;
import cc.coopersoft.keycloak.phone.providers.jpa.PhoneMessageSendEntity;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import cc.coopersoft.keycloak.phone.utils.UserUtils;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.CredentialProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.jpa.entities.RealmEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
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

    /**
     * Serialize token mutations on a row that survives token consumption.
     * Locking only a token row is insufficient: consumption deletes it and an
     * empty result cannot serialize a concurrent insert. The existing realm row
     * avoids a new lock table/migration and works across Keycloak instances.
     *
     * The lock belongs to the enclosing Keycloak request transaction, so callers
     * must not perform outbound SMS calls after acquiring it. Different numbers
     * in one realm share this lock until that transaction commits or rolls back.
     */
    private void lockTokenMutations() {
        RealmEntity realm = getEntityManager().find(
                RealmEntity.class, getRealm().getId(), LockModeType.PESSIMISTIC_WRITE);
        if (realm == null) {
            throw new IllegalStateException("Cannot mutate tokens for a missing realm");
        }
        session.setAttribute(PhoneMessageQuota.TOKEN_LOCK_HELD, Boolean.TRUE);
    }

    private TokenCodeEntity currentProcessForMutation(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        lockTokenMutations();
        TokenCodeEntity entity = currentProcessEntity(phoneNumber, tokenCodeType);
        if (entity != null) {
            // An earlier read in this request may have cached the entity before
            // another transaction incremented attempts. Reload after the lock.
            getEntityManager().refresh(entity);
            if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
                return null;
            }
        }
        return entity;
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
        lockTokenMutations();
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
                em.flush();
            }
        } catch (NoResultException ignored) {

        }
    }

    @Override
    public boolean canResend(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        LocalDateTime retryAt = resendDeadline(phoneNumber, tokenCodeType);
        return retryAt == null || !retryAt.isAfter(LocalDateTime.now());
    }

    private LocalDateTime resendDeadline(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryAt = PhoneMessageQuota.recent(getEntityManager(), getRealm().getId(), phoneNumber, tokenCodeType, now)
                .stream().map(PhoneMessageSendEntity::getResendExpiresAt).max(LocalDateTime::compareTo).orElse(null);
        TokenCodeEntity token = currentProcessEntity(phoneNumber, tokenCodeType);
        if (token != null && token.getResendExpiresAt() != null
                && (retryAt == null || token.getResendExpiresAt().isAfter(retryAt))) {
            retryAt = token.getResendExpiresAt();
        }
        return retryAt;
    }

    @Override
    public boolean isAbusing(PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        return PhoneMessageQuota.recent(getEntityManager(), getRealm().getId(), phoneNumber,
                tokenCodeType, LocalDateTime.now()).size() >= 4;
    }

    @Override
    public void persistCode(TokenCodeRepresentation tokenCode, TokenCodeType tokenCodeType, MessageSendResult sendResult) {
        // PhoneMessageService sends first; no provider HTTP call holds this lock.
        lockTokenMutations();
        // Token cleanup does not touch the independent durable sending quota.
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
     * 立即删除该手机号该类型下的验证码记录，使其作废，用户须重新发送。
     * <p>
     * 仅应在真正的验证入口（非委托重载）调用，避免重复计数。
     *
     * @param tokenCode     当前验证码托管实体
     * @param phoneNumber   手机号
     * @param tokenCodeType 验证码类型
     */
    private void registerFailedAttempt(TokenCodeEntity tokenCode, PhoneNumber phoneNumber, TokenCodeType tokenCodeType) {
        int attempts = (tokenCode.getAttempts() == null ? 0 : tokenCode.getAttempts()) + 1;
        if (attempts >= MAX_VERIFICATION_ATTEMPTS) {
            removeCode(phoneNumber, tokenCodeType);
        } else {
            tokenCode.setAttempts(attempts);
            getEntityManager().flush();
        }
    }

    @Override
    public boolean validateCode(PhoneNumber phoneNumber, String code) {
        return validateCode(phoneNumber, code, TokenCodeType.VERIFY);
    }

    @Override
    public boolean validateCode(PhoneNumber phoneNumber, String code, TokenCodeType tokenCodeType) {
        TokenCodeEntity tokenCode = currentProcessForMutation(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            return false;
        }
        if (!codesMatch(tokenCode.getCode(), code)) {
            registerFailedAttempt(tokenCode, phoneNumber, tokenCodeType);
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
        TokenCodeEntity tokenCode = currentProcessForMutation(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            return false;
        }
        if (!codesMatch(tokenCode.getCode(), code)) {
            registerFailedAttempt(tokenCode, phoneNumber, tokenCodeType);
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

        TokenCodeEntity tokenCode = currentProcessForMutation(phoneNumber, tokenCodeType);
        if (tokenCode == null) {
            throw new BadRequestException(String.format("There is no valid ongoing %s process",
                    tokenCodeType.getLabel()));
        }

        if (!codesMatch(tokenCode.getCode(), code)) {
            registerFailedAttempt(tokenCode, phoneNumber, tokenCodeType);
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
        //解绑重复的手机号
        if (UserUtils.isDuplicatePhoneAllowed()) {
            session.users().searchForUserByUserAttributeStream(session.getContext().getRealm(), "phoneNumber",
                            phoneNumber.getFullPhoneNumber()).filter(u -> !u.getId().equals(user.getId()))
                    .forEach(u -> {
                        logger.info(String.format("User %s also has phone number %s. Un-verifying.", u.getId(),
                                phoneNumber.getFullPhoneNumber()));
                        u.setSingleAttribute("phoneNumberVerified", "false");
                    });
        }

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
        LocalDateTime retryAt = resendDeadline(phoneNumber, tokenCodeType);
        if (retryAt == null || !retryAt.isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Resend timeout is finished");
        }
        return retryAt;
    }

    @Override
    public void close() {
    }
}
