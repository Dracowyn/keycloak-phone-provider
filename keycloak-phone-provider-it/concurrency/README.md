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
it does not implement an alternate generator, rate limiter, or verifier. Only the
fixture sender is replaced so no SMS is sent.

The realm row serializes token mutations for every number in that realm until
the enclosing request commits. This reduces same-realm write throughput. The separate send-quota table has a
metadata-only migration, covered by the extended fixture. SMS sending occurs before
`persistCode` takes the lock. Callers must not acquire the token lock and then
perform a remote SMS call in the same transaction.

No real SMS or external accounts are used. Random fixture credentials and OTPs
stay in driver memory or a temporary environment file with mode 0600. Container
logs and raw failure responses are not printed. Only this run's uniquely named
containers and network are removed on exit.


The fixture now also calls the real PhoneMessageService with a separate test-only
sender. It checks eight-way send contention, hourly quota after consumption and
verification exhaustion, failed/unknown delivery, outer request rollback, realm
isolation, window expiry and refusal of requests already holding the token lock.
A latch in the fake sender proves another transaction can lock the realm while
sending is blocked. Direct HTTP requests exercise the profile route switch.

--profile-update-enabled false checks explicit opt-out; true checks that the
subresource becomes reachable while retaining its existing authentication checks.
The default is to omit the setting and prove default refusal. Provider build
version must match the image tag. PostgreSQL data lives in this run's memory-only
mount, and Keycloak heap is limited. These fixtures do not send real messages or
use existing accounts, realms, databases or container names.


Use --extended for the shared-database two-instance race and legacy-schema
upgrade/restart checks. Every node is a disposable development-mode fixture with
its own heap limit; this is not a production cluster or gateway throughput test.
For the explicit-enabled control, allowUnset=false keeps the pre-existing unset
restriction active. A signed identity cookie is acquired only for the synthetic
fixture user and is never printed or saved.

For concurrent runs built against different SDK versions, --provider-dir selects
a directory containing phone.jar, dummy.jar, probe.jar and keycloak-version.txt.
The legacy-upgrade fixture gives its two synthetic token rows a 30-minute cooldown
before startup, then verifies exact metadata preservation and no sender call.
