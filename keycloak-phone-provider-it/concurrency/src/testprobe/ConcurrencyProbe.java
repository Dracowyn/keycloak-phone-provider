package testprobe;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.jpa.TokenCodeEntity;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.TokenCodeService;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import org.keycloak.Config;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.*;
import org.keycloak.models.jpa.entities.RealmEntity;
import org.keycloak.services.resource.*;

/** Test-only jar. Never included in a release provider or deployed outside disposable fixtures. */
public final class ConcurrencyProbe implements RealmResourceProviderFactory {
    public String getId() { return "concurrency-probe"; }
    public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            public Object getResource() { return new Resource(session); }
            public void close() {}
        };
    }
    public void init(Config.Scope scope) {}
    public void postInit(KeycloakSessionFactory factory) {}
    public void close() {}

    public static final class Resource {
        private static final ConcurrentMap<String, CyclicBarrier> BARRIERS = new ConcurrentHashMap<>();
        private static final ConcurrentMap<String, CountDownLatch> HOLDS = new ConcurrentHashMap<>();
        private final KeycloakSession session;
        Resource(KeycloakSession session) { this.session = session; }

        private void guard(String authorization) {
            String secret = System.getenv("PHONE_CONCURRENCY_SECRET");
            if (!"disposable-fixture".equals(System.getenv("PHONE_CONCURRENCY_MODE"))
                    || secret == null || !("Bearer " + secret).equals(authorization)
                    || !session.getContext().getRealm().getName().matches("concurrency-[ab]")) {
                throw new NotFoundException();
            }
        }
        private PhoneNumber number(String phone) {
            if (phone == null || !phone.matches("13800000[0-9]{3}")) throw new BadRequestException();
            return new PhoneNumber("86", phone);
        }
        private TokenCodeService tokens() { return session.getProvider(TokenCodeService.class); }
        private EntityManager em() { return session.getProvider(JpaConnectionProvider.class).getEntityManager(); }
        private String realmId() { return session.getContext().getRealm().getId(); }
        private void barrier(String group, int count) throws Exception {
            if (group == null || group.isBlank()) return;
            if (count < 2 || count > 8) throw new BadRequestException();
            CyclicBarrier barrier = BARRIERS.computeIfAbsent(group, key -> new CyclicBarrier(count));
            barrier.await(15, TimeUnit.SECONDS);
            BARRIERS.remove(group, barrier);
        }

        @POST @Path("seed") @Consumes("application/x-www-form-urlencoded") @Produces("application/json")
        public Map<String, Object> seed(@HeaderParam("Authorization") String authorization,
                @FormParam("phone") String phone, @DefaultValue("300") @FormParam("ttl") int ttl,
                @DefaultValue("0") @FormParam("attempts") int attempts,
                @FormParam("group") String group, @DefaultValue("2") @FormParam("count") int count)
                throws Exception {
            guard(authorization);
            if (ttl < 1 || ttl > 300 || attempts < 0 || attempts > 4) throw new BadRequestException();
            TokenCodeRepresentation token = TokenCodeRepresentation.forPhoneNumber(number(phone));
            barrier(group, count);
            tokens().persistCode(token, TokenCodeType.VERIFY, MessageSendResult.success()
                    .setExpires(LocalDateTime.now().plusSeconds(ttl))
                    .setResendExpires(LocalDateTime.now().plusSeconds(30)));
            TokenCodeEntity entity = em().find(TokenCodeEntity.class, token.getId());
            entity.setAttempts(attempts);
            return Map.of("code", token.getCode()); // response stays in test driver memory
        }

        @POST @Path("verify") @Consumes("application/x-www-form-urlencoded") @Produces("application/json")
        public Map<String, Object> verify(@HeaderParam("Authorization") String authorization,
                @FormParam("phone") String phone, @FormParam("code") String code,
                @FormParam("group") String group, @DefaultValue("2") @FormParam("count") int count,
                @DefaultValue("plain") @FormParam("mode") String mode,
                @DefaultValue("false") @FormParam("rollback") boolean rollback) throws Exception {
            guard(authorization);
            PhoneNumber number = number(phone);
            // Exercise a stale first-level persistence-context read before the realm lock.
            tokens().currentProcess(number, TokenCodeType.VERIFY);
            barrier(group, count);
            boolean valid;
            if ("plain".equals(mode)) {
                valid = tokens().validateCode(number, code, TokenCodeType.VERIFY);
            } else if ("user".equals(mode) || "bind".equals(mode)) {
                UserModel user = session.users().getUserByUsername(session.getContext().getRealm(), "fixture-owner");
                if (user == null) throw new NotFoundException();
                if ("bind".equals(mode)) {
                    try {
                        tokens().setUserPhoneNumberByCode(user, number, code);
                        valid = true;
                    } catch (BadRequestException | ForbiddenException rejected) {
                        valid = false;
                    }
                } else {
                    valid = tokens().validateCode(user, number, code, TokenCodeType.VERIFY);
                }
            } else throw new BadRequestException();
            if (rollback) session.getTransactionManager().setRollbackOnly();
            return Map.of("valid", valid);
        }

        @GET @Path("state") @Produces("application/json")
        public Map<String, Object> state(@HeaderParam("Authorization") String authorization,
                @QueryParam("phone") String phone) {
            guard(authorization);
            PhoneNumber number = number(phone);
            var rows = em().createNamedQuery("getAll", TokenCodeEntity.class)
                    .setParameter("realmId", realmId()).setParameter("areaCode", number.getAreaCode())
                    .setParameter("phoneNumber", number.getPhoneNumber()).setParameter("type", "VERIFY")
                    .getResultList();
            return Map.of("rows", rows.size(), "attempts", rows.stream()
                    .mapToInt(row -> row.getAttempts() == null ? 0 : row.getAttempts()).sum());
        }

        @POST @Path("hold") @Produces("application/json")
        public Map<String, Object> hold(@HeaderParam("Authorization") String authorization) throws Exception {
            guard(authorization);
            em().find(RealmEntity.class, realmId(), LockModeType.PESSIMISTIC_WRITE);
            CountDownLatch release = new CountDownLatch(1);
            if (HOLDS.putIfAbsent(realmId(), release) != null) throw new BadRequestException();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture lock timed out");
            } finally { HOLDS.remove(realmId(), release); }
            return Map.of("released", true);
        }
        @GET @Path("held") @Produces("application/json")
        public Map<String, Object> held(@HeaderParam("Authorization") String authorization) {
            guard(authorization);
            return Map.of("held", HOLDS.containsKey(realmId()));
        }
        @POST @Path("release")
        public Response release(@HeaderParam("Authorization") String authorization) {
            guard(authorization);
            CountDownLatch hold = HOLDS.get(realmId());
            if (hold != null) hold.countDown();
            return Response.noContent().build();
        }
    }
}
