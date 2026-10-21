# Token mutation concurrency regression

This opt-in regression uses the actual phone provider in a disposable Keycloak
with PostgreSQL. Each HTTP request uses a separate Keycloak/JPA transaction. The
probe jar is built separately and is never copied into release artifacts. It is
gated by a random test secret, an explicit fixture-mode environment variable,
synthetic realm names, and a synthetic number range.

Compile without running containers:

```sh
python3 keycloak-phone-provider-it/concurrency/build.py --keycloak 26.2.5
```

After review, run the isolated runtime regression (Docker required):

```sh
python3 keycloak-phone-provider-it/concurrency/verify.py
```

For the upstream version, rebuild with `--keycloak 26.7.0` and run with
`--keycloak-image quay.io/keycloak/keycloak:26.7.0`.

Checks include eight-way single consumption, stale first-level JPA reads,
concurrent failed-attempt accounting, wrong/correct races, persistence competing
with consumption, old-code replay, transaction rollback, lock-wait expiry,
independent realms, and both user-aware validation entry points. Assertions
require successful HTTP responses rather than accepting server errors as a
rejected code. The fixture calls the upstream generator and persistence service;
it does not implement an alternate generator, sender, rate limiter, or verifier.

The realm row serializes token mutations for every number in that realm until
the enclosing request commits. This is a deliberate minimal fix without a schema
migration; it reduces same-realm write throughput. SMS sending occurs before
`persistCode` takes the lock. Callers must not acquire the token lock and then
perform a remote SMS call in the same transaction.

No real SMS or external accounts are used. Random fixture credentials and OTPs
stay in driver memory or a temporary environment file with mode 0600. Container
logs and raw failure responses are not printed. Only this run's uniquely named
containers and network are removed on exit.
