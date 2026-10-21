#!/usr/bin/env python3
"""Opt-in isolated PostgreSQL/Keycloak concurrency regression. Uses no real accounts or SMS."""
import argparse
import concurrent.futures
import hashlib
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
args = parser.parse_args()
prefix = "phone-concurrency-" + secrets.token_hex(6)
network, postgres, keycloak = prefix + "-net", prefix + "-pg", prefix + "-kc"
password, secret = secrets.token_urlsafe(40), secrets.token_urlsafe(40)
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
        shutil.copyfile(HERE / "target" / filename, temporary / filename)
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
        docker("run", "-d", "--name", postgres, "--network", network, "--env-file", str(env), args.postgres_image)
        for _ in range(60):
            ready = docker("exec", postgres, "pg_isready", "-U", "postgres", check=False)
            if "accepting connections" in ready:
                break
            time.sleep(1)
        else:
            raise RuntimeError("PostgreSQL fixture did not become ready")
        command = ["run", "-d", "--name", keycloak, "--network", network, "--env-file", str(env),
                   "-p", "127.0.0.1:" + str(port) + ":8080", "-e", "KC_DB=postgres",
                   "-e", "KC_DB_USERNAME=postgres", "-e", "KC_DB_URL=jdbc:postgresql://" + postgres + ":5432/keycloak"]
        for filename in ["phone.jar", "dummy.jar", "probe.jar"]:
            command += ["-v", str(temporary / filename) + ":/opt/keycloak/providers/" + filename + ":ro"]
        command += ["-v", str(temporary / "areacode.json") + ":/areacode.json:ro", args.keycloak_image,
                    "start-dev", "--hostname=" + base]
        docker(*command)
        for _ in range(300):
            try:
                if call(base + "/realms/master/.well-known/openid-configuration")[0] == 200:
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Keycloak fixture did not become ready; no raw container logs retained")
        _, token = call(base + "/realms/master/protocol/openid-connect/token", form={
            "client_id": "admin-cli", "grant_type": "password", "username": "fixture-admin", "password": password})
        admin_headers = {"Authorization": "Bearer " + token["access_token"]}
        def admin(path, body=None, method=None):
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
                  "attributes": {"phoneNumber": ["+86 13800000800"]}})
        for realm in ["concurrency-a", "concurrency-b"]:
            fixture_user, = admin(realm + "/users?username=fixture-owner&exact=true")
            check("fixture-owner-number-" + realm,
                  fixture_user.get("attributes", {}).get("phoneNumber") == ["+86 13800000800"])
        headers = {"Authorization": "Bearer " + secret}
        def probe(path, form=None, realm="concurrency-a", method=None):
            return call(base + "/realms/" + realm + "/concurrency-probe/" + path,
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
        print(json.dumps({"passed": all(results.values()), "checks": results,
                          "keycloak_image": args.keycloak_image, "database": "PostgreSQL",
                          "real_sms_sent": False, "production_changed": False}, indent=2))
    finally:
        print(json.dumps({"checks_completed": results, "keycloak_image": args.keycloak_image, "real_sms_sent": False, "production_changed": False}))
        for container in [keycloak, postgres]:
            docker("rm", "-f", container, check=False)
        docker("network", "rm", network, check=False)
