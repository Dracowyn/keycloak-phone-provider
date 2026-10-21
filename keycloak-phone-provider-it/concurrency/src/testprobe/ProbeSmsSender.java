package testprobe;

import cc.coopersoft.keycloak.phone.providers.constants.*;
import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import cc.coopersoft.keycloak.phone.providers.spi.*;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.keycloak.Config;
import org.keycloak.models.*;

/** Test-only counter: never calls a gateway, prints an OTP, or ships in a release jar. */
public final class ProbeSmsSender implements MessageSenderServiceProviderFactory {
    static final ConcurrentMap<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    static final ConcurrentMap<String, String> MODES = new ConcurrentHashMap<>();
    static final ConcurrentMap<String, CountDownLatch> BLOCKS = new ConcurrentHashMap<>();
    static String key(KeycloakSession session, PhoneNumber phone) {
        return session.getContext().getRealm().getId() + ":" + phone.getPhoneNumber();
    }
    public String getId() { return "fixture-no-sms"; }
    public MessageSenderService create(KeycloakSession session) {
        return new MessageSenderService() {
            public MessageSendResult sendSmsMessage(TokenCodeType type, PhoneNumber phone, String code, int expires)
                    throws MessageSendException {
                if (!"disposable-fixture".equals(System.getenv("PHONE_CONCURRENCY_MODE"))
                        || !session.getContext().getRealm().getName().matches("concurrency-[ab]")
                        || !phone.getPhoneNumber().matches("13800000[0-9]{3}")) {
                    throw new IllegalStateException("Fixture sender disabled");
                }
                String key = key(session, phone);
                CALLS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                String mode = MODES.getOrDefault(key, "ok");
                if ("fail".equals(mode)) throw new MessageSendException(-1, "fixture_failure", "Simulated failure");
                if ("block".equals(mode)) {
                    CountDownLatch release = new CountDownLatch(1);
                    BLOCKS.put(key, release);
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture sender timed out");
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    finally { BLOCKS.remove(key, release); }
                }
                return MessageSendResult.success().setExpires(expires).setResendExpires(60);
            }
            public void close() {}
        };
    }
    public void init(Config.Scope config) {}
    public void postInit(KeycloakSessionFactory factory) {}
    public void close() {}
}
