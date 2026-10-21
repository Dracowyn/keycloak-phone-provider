package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.jpa.PhoneMessageSendEntity;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.ForbiddenException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.jpa.entities.RealmEntity;
import org.keycloak.models.utils.KeycloakModelUtils;

/** Durable pre-send reservation. No remote call is made while holding a quota lock. */
final class PhoneMessageQuota {
    static final String TOKEN_LOCK_HELD = PhoneMessageQuota.class.getName() + ".tokenLockHeld";
    private static final int MAX_SENDS_PER_HOUR = 4;
    private static final int MIN_RESEND_SECONDS = 60;
    private final KeycloakSession session;
    private final String realmId;

    PhoneMessageQuota(KeycloakSession session) {
        this.session = session;
        this.realmId = session.getContext().getRealm().getId();
    }

    static List<PhoneMessageSendEntity> recent(EntityManager em, String realmId,
            PhoneNumber phone, TokenCodeType type, LocalDateTime now) {
        return em.createNamedQuery("phoneSendsSince", PhoneMessageSendEntity.class)
                .setParameter("realmId", realmId).setParameter("areaCode", phone.getAreaCode())
                .setParameter("phoneNumber", phone.getPhoneNumber()).setParameter("type", type.name())
                .setParameter("since", now.minusHours(1)).getResultList();
    }

    private <T> T committed(Function<EntityManager, T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        // A new session alone may join the request's JTA transaction. Suspend it
        // explicitly: even a later request rollback must not refund a sent SMS.
        KeycloakModelUtils.suspendJtaTransaction(session.getKeycloakSessionFactory(), () ->
            result.set(KeycloakModelUtils.runJobInTransactionWithResult(session.getKeycloakSessionFactory(), isolated -> {
                EntityManager em = isolated.getProvider(JpaConnectionProvider.class).getEntityManager();
                if (em.find(RealmEntity.class, realmId, LockModeType.PESSIMISTIC_WRITE) == null) {
                    throw new IllegalStateException("Cannot reserve sends for a missing realm");
                }
                return work.apply(em);
            })));
        return result.get();
    }

    /** null permits this reservation; a timestamp means wait without calling a sender. */
    LocalDateTime reserve(PhoneNumber phone, TokenCodeType type, String id) {
        if (Boolean.TRUE.equals(session.getAttribute(TOKEN_LOCK_HELD))) {
            throw new IllegalStateException("Send before mutating verification tokens in this request");
        }
        return committed(em -> {
            LocalDateTime now = LocalDateTime.now();
            em.createQuery("DELETE FROM PhoneMessageSendEntity s WHERE s.realmId = :realmId AND s.createdAt < :cutoff")
                    .setParameter("realmId", realmId).setParameter("cutoff", now.minusHours(1)).executeUpdate();
            List<PhoneMessageSendEntity> sends = recent(em, realmId, phone, type, now);
            if (sends.size() >= MAX_SENDS_PER_HOUR) {
                throw new ForbiddenException("You requested the maximum number of messages the last hour");
            }
            LocalDateTime retryAt = sends.stream().map(PhoneMessageSendEntity::getResendExpiresAt)
                    .max(LocalDateTime::compareTo).orElse(null);
            if (retryAt != null && retryAt.isAfter(now)) return retryAt;
            PhoneMessageSendEntity send = new PhoneMessageSendEntity();
            send.setId(id); send.setRealmId(realmId); send.setAreaCode(phone.getAreaCode());
            send.setPhoneNumber(phone.getPhoneNumber()); send.setType(type.name()); send.setCreatedAt(now);
            // Until delivery returns, retries stay blocked. A crash/unknown outcome
            // conservatively consumes this hour's quota; it cannot trigger a resend.
            send.setResendExpiresAt(now.plusHours(1));
            em.persist(send);
            return null;
        });
    }

    void completed(String id, LocalDateTime vendorRetryAt) {
        committed(em -> {
            PhoneMessageSendEntity send = em.find(PhoneMessageSendEntity.class, id);
            if (send == null || !realmId.equals(send.getRealmId())) {
                throw new IllegalStateException("Missing committed send reservation");
            }
            LocalDateTime minimum = LocalDateTime.now().plusSeconds(MIN_RESEND_SECONDS);
            send.setResendExpiresAt(vendorRetryAt != null && vendorRetryAt.isAfter(minimum) ? vendorRetryAt : minimum);
            return null;
        });
    }
}
