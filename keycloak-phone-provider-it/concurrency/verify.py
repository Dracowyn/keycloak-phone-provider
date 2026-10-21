#!/usr/bin/env python3
"""Opt-in isolated PostgreSQL/Keycloak concurrency regression. Uses no real accounts or SMS."""
import argparse
import concurrent.futures
import hashlib
import html
import http.cookiejar
import re
import threading
import json
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--keycloak-image", default="quay.io/keycloak/keycloak:26.2.5")
parser.add_argument("--postgres-image", default="postgres:17")
parser.add_argument("--provider-dir", type=Path, default=HERE / "target", help="Built fixture jars and matching keycloak-version.txt")
parser.add_argument("--extended", action="store_true", help="Also test two instances and legacy-schema migration/restart")
parser.add_argument("--profile-update-enabled", choices=["default", "false", "true"], default="default")
args = parser.parse_args()
built = (args.provider_dir / "keycloak-version.txt").read_text().strip()
if args.keycloak_image.rsplit(":", 1)[-1] != built:
    raise RuntimeError("Fixture provider and Keycloak image versions must match")
prefix = "phone-concurrency-" + secrets.token_hex(6)
network, postgres, keycloak = prefix + "-net", prefix + "-pg", prefix + "-kc"
password, secret = secrets.token_urlsafe(40), secrets.token_urlsafe(40)
keycloak_names = [keycloak]
results = {}

def docker(*argv, check=True):
    result = subprocess.run(["docker", *argv], capture_output=True, text=True, timeout=90)
    if check and result.returncode:
        raise RuntimeError("Fixture docker operation failed: " + argv[0])
    return result.stdout

def call(url, form=None, body=None, method=None, headers=None, timeout=30):
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read() or "null")
    except urllib.error.HTTPError as error:
        # Do not report a response that might contain the fixture OTP or credentials.
        raise AssertionError("Fixture HTTP " + str(error.code)) from None

def check(name, condition):
    results[name] = bool(condition)
    if not condition:
        raise AssertionError("Regression failed: " + name)

with tempfile.TemporaryDirectory(prefix=prefix) as temporary:
    temporary = Path(temporary)
    for filename in ["phone.jar", "dummy.jar", "probe.jar"]:
        shutil.copyfile(args.provider_dir / filename, temporary / filename)
    shutil.copyfile(HERE.parent / "src/test/resources/areacode.json", temporary / "areacode.json")
    env = temporary / "fixture.env"
    env.write_text("POSTGRES_PASSWORD=" + password + "\nPOSTGRES_DB=keycloak\n"
                   "KC_DB_PASSWORD=" + password + "\nKC_BOOTSTRAP_ADMIN_USERNAME=fixture-admin\n"
                   "KC_BOOTSTRAP_ADMIN_PASSWORD=" + password + "\n"
                   "PHONE_CONCURRENCY_MODE=disposable-fixture\nPHONE_CONCURRENCY_SECRET=" + secret + "\n")
    env.chmod(0o600)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    base = "http://127.0.0.1:" + str(port)
    try:
        docker("network", "create", network)
        docker("run", "-d", "--name", postgres, "--network", network, "--env-file", str(env),
               "--tmpfs", "/var/lib/postgresql/data:rw,size=512m", args.postgres_image)
        for _ in range(60):
            ready = docker("exec", postgres, "pg_isready", "-U", "postgres", check=False)
            if "accepting connections" in ready:
                break
            time.sleep(1)
        else:
            raise RuntimeError("PostgreSQL fixture did not become ready")
        command = ["run", "-d", "--memory=1g", "--tmpfs", "/tmp:rw,size=256m", "--tmpfs", "/opt/keycloak/data:rw,size=256m,mode=1777", "--name", keycloak, "--network", network, "--env-file", str(env),
                   "-p", "127.0.0.1:" + str(port) + ":8080", "-e", "KC_DB=postgres",
                   "-e", "JAVA_OPTS_KC_HEAP=-Xms128m -Xmx384m",
                   "-e", "KC_DB_USERNAME=postgres", "-e", "KC_DB_URL=jdbc:postgresql://" + postgres + ":5432/keycloak"]
        for filename in ["phone.jar", "dummy.jar", "probe.jar"]:
            command += ["-v", str(temporary / filename) + ":/opt/keycloak/providers/" + filename + ":ro"]
        command += ["-v", str(temporary / "areacode.json") + ":/areacode.json:ro", args.keycloak_image,
                    "start-dev", "--hostname=" + base, "--spi-phone-provider-config-sender-service=fixture-no-sms"]
        if args.profile_update_enabled != "default":
            command += ["--spi-phone-provider-config-profile-update-enabled=" + args.profile_update_enabled]
        if args.profile_update_enabled == "true":
            command += ["--spi-phone-provider-config-allow-unset=false"]
        docker(*command)
        def wait_ready(name, url):
            for _ in range(300):
                if docker("inspect", "--format", "{{.State.Running}}", name, check=False).strip() == "false":
                    raise RuntimeError("Keycloak fixture exited before becoming ready; no raw logs printed")
                try:
                    if call(url + "/realms/master/.well-known/openid-configuration")[0] == 200: return
                except Exception: pass
                time.sleep(1)
            raise RuntimeError("Keycloak fixture did not become ready; no raw container logs retained")
        wait_ready(keycloak, base)
        _, token = call(base + "/realms/master/protocol/openid-connect/token", form={
            "client_id": "admin-cli", "grant_type": "password", "username": "fixture-admin", "password": password})
        admin_headers = {"Authorization": "Bearer " + token["access_token"]}
        admin_state = {"until": time.monotonic() + token.get("expires_in", 60) - 10}
        def ensure_admin():
            if time.monotonic() >= admin_state["until"]:
                _, renewed = call(base + "/realms/master/protocol/openid-connect/token", form={
                    "client_id": "admin-cli", "grant_type": "password", "username": "fixture-admin", "password": password})
                admin_headers["Authorization"] = "Bearer " + renewed["access_token"]
                admin_state["until"] = time.monotonic() + renewed.get("expires_in", 60) - 10
        server_info = call(base + "/admin/serverinfo", headers=admin_headers)[1]
        check("runtime-version-matches-provider", server_info["systemInfo"]["version"] == built)
        def admin(path, body=None, method=None):
            ensure_admin()
            return call(base + "/admin/realms/" + path, body=body, method=method, headers=admin_headers)[1]
        for realm in ["concurrency-a", "concurrency-b"]:
            admin("", {"realm": realm, "enabled": True, "sslRequired": "none"})
            # Keycloak drops undeclared user attributes. Declare these synthetic
            # fields only in the disposable realms used by this fixture.
            profile = admin(realm + "/users/profile")
            profile["attributes"] += [
                {"name": name, "permissions": {"view": ["admin"], "edit": ["admin"]}}
                for name in ["phoneNumber", "phoneNumberVerified"]]
            admin(realm + "/users/profile", profile, method="PUT")
            admin(realm + "/users", {"username": "fixture-owner", "enabled": True,
                  "firstName": "Fixture", "lastName": "Owner", "email": "fixture-owner@example.invalid", "emailVerified": True,
                  "attributes": {"phoneNumber": ["+86 13800000800"]}})
        for realm in ["concurrency-a", "concurrency-b"]:
            fixture_user, = admin(realm + "/users?username=fixture-owner&exact=true")
            check("fixture-owner-number-" + realm,
                  fixture_user.get("attributes", {}).get("phoneNumber") == ["+86 13800000800"])
        headers = {"Authorization": "Bearer " + secret}
        def probe(path, form=None, realm="concurrency-a", method=None, node=None):
            return call((node or base) + "/realms/" + realm + "/concurrency-probe/" + path,
                        form=form, headers=headers, method=method)[1]
        def seed(phone, **options):
            return probe("seed", {"phone": phone, **options})["code"]
        def verify(phone, code, **options):
            return probe("verify", {"phone": phone, "code": code, **options})["valid"]
        def state(phone):
            return probe("state?phone=" + phone)
        def race(calls):
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(calls)) as pool:
                return list(pool.map(lambda function: function(), calls))
        def wrong(code):
            return "000000" if code != "000000" else "999999"
        def snapshot():
            return hashlib.sha256(json.dumps({realm: admin(realm + "/users") for realm in
                ["concurrency-a", "concurrency-b"]}, sort_keys=True).encode()).hexdigest()
        # Obtain an actual signed Keycloak identity cookie using only this run's
        # synthetic user, then prove it cannot bypass the server-side switch.
        admin("concurrency-a/clients", {"clientId": "fixture-browser", "publicClient": True,
              "standardFlowEnabled": True, "redirectUris": [base + "/fixture/callback"]})
        owner, = admin("concurrency-a/users?username=fixture-owner&exact=true")
        admin("concurrency-a/users/" + owner["id"] + "/reset-password",
              {"type": "password", "value": password, "temporary": False}, method="PUT")
        class FixtureCookiePolicy(http.cookiejar.DefaultCookiePolicy):
            def return_ok_secure(self, cookie, request):
                # Browsers treat loopback origins as trustworthy. Keep this
                # exception restricted to the disposable fixture's exact origin.
                if urllib.parse.urlsplit(request.get_full_url()).netloc == urllib.parse.urlsplit(base).netloc:
                    return True
                return super().return_ok_secure(cookie, request)
        jar = http.cookiejar.CookieJar(FixtureCookiePolicy())
        browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        auth_url = base + "/realms/concurrency-a/protocol/openid-connect/auth?" + urllib.parse.urlencode({
            "client_id": "fixture-browser", "response_type": "code", "scope": "openid",
            "redirect_uri": base + "/fixture/callback", "state": "fixture-state"})
        with browser.open(auth_url, timeout=15) as response: login_html = response.read().decode()
        action_match = re.search(r'<form[^>]*action="([^"]+)"', login_html)
        if action_match is None: raise AssertionError("Fixture browser login form unavailable")
        login_request = urllib.request.Request(html.unescape(action_match.group(1)),
            data=urllib.parse.urlencode({"username": "fixture-owner", "password": password}).encode())
        try:
            with browser.open(login_request, timeout=15) as response: callback_url = response.url
        except urllib.error.HTTPError as error:
            if error.code != 404 or not error.url.startswith(base + "/fixture/callback?"):
                failure_page = error.read().decode(errors="replace")
                markers = [name for name, text in [("cookie-missing", "Cookie not found"),
                    ("expired", "expired"), ("invalid", "Invalid"), ("profile", "Update Account")]
                    if text in failure_page]
                print(json.dumps({"fixture_login_http": error.code, "markers": markers,
                    "cookie_metadata": [{"name": c.name, "secure": c.secure, "path": c.path} for c in jar]}))
                raise AssertionError("Fixture browser login failed: HTTP " + str(error.code)
                    + " path " + urllib.parse.urlsplit(error.url).path) from None
            callback_url = error.url
        check("fixture-real-identity-cookie", "code=" in callback_url and any(c.name == "KEYCLOAK_IDENTITY" for c in jar))
        cookie_users_before = snapshot()
        for method in ["POST", "DELETE"]:
            request = urllib.request.Request(base + "/realms/concurrency-a/sms/update-profile",
                data=b"{}" if method == "POST" else None, headers={"Content-Type": "application/json"}, method=method)
            try:
                with browser.open(request, timeout=10) as response: status = response.status
            except urllib.error.HTTPError as error: status = error.code
            expected = status != 404 if args.profile_update_enabled == "true" else status == 404
            check("profile-route-" + method + "-real-cookie", expected and status < 500)
        check("profile-route-real-cookie-keeps-users", snapshot() == cookie_users_before)


        initial = snapshot()
        # Repeated real HTTP requests each enlist separate Keycloak/JPA transactions.
        for iteration in range(10):
            phone = "13800000" + str(100 + iteration)
            code = seed(phone)
            group = secrets.token_hex(8)
            out = race([lambda: verify(phone, code, group=group, count=8) for _ in range(8)])
            check("eight-way-single-consumption-" + str(iteration), sum(out) == 1 and not verify(phone, code))
        phone = "13800000200"
        code = seed(phone, attempts=3)
        group = secrets.token_hex(8)
        out = race([lambda: verify(phone, wrong(code), group=group) for _ in range(2)])
        check("concurrent-failures-reach-five", not any(out) and state(phone)["rows"] == 0 and not verify(phone, code))
        phone = "13800000201"
        code = seed(phone, attempts=4)
        group = secrets.token_hex(8)
        out = race([lambda: verify(phone, code, group=group), lambda: verify(phone, wrong(code), group=group)])
        check("wrong-correct-race-is-linear", not out[1] and state(phone)["rows"] == 0 and not verify(phone, code))
        for iteration in range(5):
            phone = "13800000" + str(300 + iteration)
            old = seed(phone)
            group = secrets.token_hex(8)
            out = race([lambda: verify(phone, old, group=group), lambda: seed(phone, group=group)])
            check("concurrent-persist-not-deleted-" + str(iteration), verify(phone, out[1]) and not verify(phone, old))
        phone = "13800000400"
        old, current = seed(phone), seed(phone)
        while current == old:
            current = seed(phone)
        check("old-code-does-not-resurrect", not verify(phone, old) and verify(phone, current) and not verify(phone, old))
        phone = "13800000401"
        code = seed(phone)
        check("request-rollback-preserves-token", verify(phone, code, rollback="true") and verify(phone, code))
        check("no-user-overload-keeps-all-users", snapshot() == initial)
        # Hold realm A in a separate HTTP/JPA transaction, force a waiter past expiry,
        # and prove realm B remains usable while A is locked.
        phone = "13800000500"
        code = seed(phone, ttl=2)
        other = probe("seed", {"phone": phone}, realm="concurrency-b")["code"]
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            holder = pool.submit(lambda: probe("hold", method="POST"))
            for _ in range(100):
                if probe("held")["held"]:
                    break
                time.sleep(0.05)
            else:
                raise RuntimeError("Fixture lock was not acquired")
            waiter = pool.submit(lambda: verify(phone, code))
            try:
                check("different-realm-independent", probe("verify", {"phone": phone, "code": other}, realm="concurrency-b")["valid"])
                time.sleep(2.2)
                check("waiter-waits-for-database-lock", not waiter.done())
            finally:
                probe("release", method="POST")
            holder.result()
            check("expiry-rechecked-after-lock", not waiter.result())
        for mode, phone in [("user", "13800000800"), ("bind", "13800000801")]:
            code = seed(phone)
            group = secrets.token_hex(8)
            out = race([lambda: verify(phone, code, group=group, mode=mode) for _ in range(2)])
            check(mode + "-overload-single-consumption", sum(out) == 1 and not verify(phone, code))
        # Exercise the real sending service, with a test-only sender that never sends SMS.
        def send(phone, **options):
            return probe("send", {"phone": phone, **options})["status"]
        def quota(phone, realm="concurrency-a"):
            return probe("quota?phone=" + phone, realm=realm)
        def elapsed_cooldown(phone, **options):
            return probe("cooldown", {"phone": phone, **options})
        phone = "13800000900"
        group = secrets.token_hex(8)
        out = race([lambda: send(phone, group=group) for _ in range(8)])
        check("send-eight-way-one-supplier-call", out.count(1) == 1 and out.count(-2) == 7
              and quota(phone)["rows"] == 1 and quota(phone)["senderCalls"] == 1)
        code = probe("current?phone=" + phone)["code"]
        check("consumption-keeps-hourly-send-record", verify(phone, code) and quota(phone)["rows"] == 1
              and not quota(phone)["canResend"])
        for index in range(1, 4):
            elapsed_cooldown(phone)
            check("send-after-cooldown-" + str(index), send(phone) == 1)
            code = probe("current?phone=" + phone)["code"]
            check("consume-keeps-quota-" + str(index), verify(phone, code) and quota(phone)["rows"] == index + 1)
        elapsed_cooldown(phone)
        check("fifth-send-denied-after-four-consumptions", send(phone) == 403
              and quota(phone)["senderCalls"] == 4 and quota(phone)["abusing"])
        check("send-quota-independent-realm", probe("send", {"phone": phone}, realm="concurrency-b")["status"] == 1
              and quota(phone, "concurrency-b")["rows"] == 1)
        elapsed_cooldown(phone, ageHour="true")
        check("expired-hour-does-not-exhaust-current-quota", send(phone) == 1 and quota(phone)["rows"] == 1)
        phone = "13800000901"
        check("send-success-before-outer-rollback", send(phone, rollback="true") == 1)
        check("rollback-does-not-refund-sms-quota", state(phone)["rows"] == 0 and quota(phone)["rows"] == 1
              and send(phone) == -2 and quota(phone)["senderCalls"] == 1)
        phone = "13800000902"
        check("send-for-failed-attempt-test", send(phone) == 1)
        code = probe("current?phone=" + phone)["code"]
        for _ in range(5): verify(phone, wrong(code))
        check("wrong-code-exhaustion-keeps-sending-quota", state(phone)["rows"] == 0 and quota(phone)["rows"] == 1
              and send(phone) == -2 and quota(phone)["senderCalls"] == 1)
        phone = "13800000903"
        probe("sender-mode", {"phone": phone, "mode": "fail"})
        check("failed-sender-keeps-quota", send(phone) == -1 and quota(phone)["rows"] == 1
              and quota(phone)["retrySeconds"] > 3500 and send(phone) == -2 and quota(phone)["senderCalls"] == 1)
        phone = "13800000904"
        check("already-locked-request-refuses-send", send(phone, locked="true") == 409
              and quota(phone)["senderCalls"] == 0 and quota(phone)["rows"] == 0)
        phone = "13800000905"
        probe("sender-mode", {"phone": phone, "mode": "block"})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            sending = pool.submit(lambda: send(phone))
            for _ in range(100):
                if quota(phone)["blockedSender"]: break
                time.sleep(0.05)
            else: raise RuntimeError("Fixture sender was not entered")
            holder = pool.submit(lambda: probe("hold", method="POST"))
            try:
                for _ in range(50):
                    if probe("held")["held"]: break
                    time.sleep(0.05)
                else: raise RuntimeError("Sender kept the realm database lock during its remote work")
                check("outbound-sender-does-not-hold-realm-lock", not sending.done())
            finally:
                probe("release", method="POST")
                probe("sender-release", {"phone": phone})
            holder.result()
            check("blocked-sender-can-complete", sending.result() == 1)
        # All mutating subresource methods must be rejected by the server switch,
        # before cookie authentication, form parsing, or user updates.
        users_before_route_checks = snapshot()
        for method in ["GET", "POST", "DELETE"]:
            for auth in [{}, admin_headers]:
                url = base + "/realms/concurrency-a/sms/update-profile"
                request = urllib.request.Request(url, data=b"{}" if method == "POST" else None,
                    headers={**auth, "Content-Type": "application/json"}, method=method)
                try:
                    with urllib.request.urlopen(request, timeout=10) as response: status = response.status
                except urllib.error.HTTPError as error: status = error.code
                expected = status != 404 if args.profile_update_enabled == "true" else status == 404
                check("profile-route-" + method + ("-bearer-present" if auth else "-anonymous"), expected and status < 500)
        check("profile-route-checks-do-not-change-users", snapshot() == users_before_route_checks)
        if args.extended:
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0)); replica_port = listener.getsockname()[1]
            replica, replica_base = keycloak + "-replica", "http://127.0.0.1:" + str(replica_port)
            keycloak_names.append(replica)
            replica_command = list(command)
            replica_command[replica_command.index("--name") + 1] = replica
            replica_command[replica_command.index("-p") + 1] = "127.0.0.1:" + str(replica_port) + ":8080"
            # Nodes share the same public issuer, as they would behind one ingress.
            docker(*replica_command)
            wait_ready(replica, replica_base)
            ensure_admin()
            check("second-runtime-version-matches-provider",
                  call(replica_base + "/admin/serverinfo", headers=admin_headers)[1]["systemInfo"]["version"] == built)
            phone = "13800000906"
            start = threading.Barrier(8)
            def distributed_send(node):
                start.wait(10)
                return probe("send", {"phone": phone}, node=node)["status"]
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                out = list(pool.map(distributed_send, [base, replica_base] * 4))
            supplier_calls = sum(probe("quota?phone=" + phone, node=node)["senderCalls"] for node in [base, replica_base])
            check("two-instance-eight-way-one-supplier-call", out.count(1) == 1 and out.count(-2) == 7
                  and supplier_calls == 1 and quota(phone)["rows"] == 1)
            # Simulate the exact old database shape while every fixture KC node
            # is stopped. The actual provider's Liquibase upgrade does the copy.
            seed("13800000907"); seed("13800000908")
            def db(sql):
                return docker("exec", postgres, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                              "-U", "postgres", "-d", "keycloak", "-c", sql).strip()
            log_tables = db("SELECT DISTINCT t.table_name FROM information_schema.tables t JOIN information_schema.columns c ON c.table_schema=t.table_schema AND c.table_name=t.table_name WHERE t.table_schema='public' AND t.table_name LIKE 'databasechangelog%' AND c.column_name='author'").splitlines()
            markers = []
            for table in log_tables:
                if not re.fullmatch(r"databasechangelog[a-z0-9_]*", table): raise AssertionError("Unexpected fixture migration table")
                if db('SELECT count(*) FROM "' + table + '" WHERE id=\'phone-message-send-quota\' AND author=\'dracowyn\'') == "1": markers.append(table)
            check("quota-migration-marker-present", len(markers) == 1)
            for node in [replica, keycloak]: docker("rm", "-f", node)
            db('DELETE FROM "' + markers[0] + '" WHERE id=\'phone-message-send-quota\' AND author=\'dracowyn\'; DROP TABLE phone_message_send')
            # seed() uses a 30-second cooldown, shorter than a cold KC start.
            # Give these two synthetic legacy rows a live cooldown before upgrade;
            # the migration must preserve it exactly, never manufacture a new one.
            db("UPDATE phone_message_token_code SET resend_expires_at = LOCALTIMESTAMP + INTERVAL '30 minutes' WHERE phone_number IN ('13800000907','13800000908')")
            expected_legacy_rows = int(db("SELECT count(*) FROM phone_message_token_code"))
            check("legacy-upgrade-has-existing-token-rows", expected_legacy_rows >= 2)
            docker(*command); wait_ready(keycloak, base)
            columns = "id,realm_id,area_code,phone_number,type,created_at,resend_expires_at"
            missing = db("SELECT count(*) FROM (SELECT " + columns + " FROM phone_message_token_code EXCEPT SELECT " + columns + " FROM phone_message_send) old_rows")
            extra = db("SELECT count(*) FROM (SELECT " + columns + " FROM phone_message_send EXCEPT SELECT " + columns + " FROM phone_message_token_code) extra_rows")
            check("upgrade-copies-existing-send-metadata-exactly", missing == extra == "0"
                  and int(db("SELECT count(*) FROM phone_message_send")) == expected_legacy_rows)
            check("upgrade-fixture-cooldown-still-live", quota("13800000907")["retrySeconds"] > 0)
            check("upgrade-preserves-existing-cooldown", send("13800000907") == -2
                  and quota("13800000907")["senderCalls"] == 0)
            quota_hash_sql = "SELECT md5(coalesce(string_agg(id||created_at::text||resend_expires_at::text,'|' ORDER BY id),'')) FROM phone_message_send"
            quota_hash = db(quota_hash_sql)
            docker("rm", "-f", keycloak); docker(*command); wait_ready(keycloak, base)
            check("restart-does-not-reset-quota", db(quota_hash_sql) == quota_hash)

        print(json.dumps({"passed": all(results.values()), "checks": results,
                          "keycloak_image": args.keycloak_image, "database": "PostgreSQL",
                          "real_sms_sent": False, "production_changed": False}, indent=2))
    finally:
        print(json.dumps({"checks_completed": results, "keycloak_image": args.keycloak_image, "real_sms_sent": False, "production_changed": False}))
        for container in [*keycloak_names, postgres]:
            docker("rm", "-f", container, check=False)
        docker("network", "rm", network, check=False)
