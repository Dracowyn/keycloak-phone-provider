#!/usr/bin/env python3
"""Compile the actual provider and a separate disposable-fixture probe. Does not run containers."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--maven", default="mvn")
parser.add_argument("--settings")
parser.add_argument("--keycloak", default="26.2.5")
args = parser.parse_args()
target = HERE / "target"
target.mkdir(exist_ok=True)
maven = [args.maven, "-B", "-f", str(ROOT / "pom.xml")]
if args.settings:
    maven += ["-s", args.settings]
maven += ["-Dversion.keycloak=" + args.keycloak, "-Dmaven.javadoc.skip=true"]
subprocess.run(maven + ["-pl", "keycloak-sms-provider-dummy", "-am", "clean", "package"], check=True)
classpath = target / "classpath.txt"
subprocess.run(maven + ["-pl", "keycloak-phone-provider", "dependency:build-classpath",
                       "-Dmdep.outputFile=" + str(classpath), "-DincludeScope=compile"], check=True)
classes = target / "classes"
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir()
java_bin = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
provider = ROOT / "keycloak-phone-provider/target/keycloak-phone-provider-2.1.0.jar"
cp = str(provider) + os.pathsep + classpath.read_text().strip()
subprocess.run([str(java_bin / "javac") if java_bin else "javac", "--release", "17", "-proc:none",
                "-cp", cp, "-d", str(classes), *[str(p) for p in sorted((HERE / "src/testprobe").glob("*.java"))]], check=True)
service = classes / "META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory"
service.parent.mkdir(parents=True)
service.write_text("testprobe.ConcurrencyProbe\n")
sender_service = classes / "META-INF/services/cc.coopersoft.keycloak.phone.providers.spi.MessageSenderServiceProviderFactory"
sender_service.write_text("testprobe.ProbeSmsSender\n")
theme = classes / "theme/phone-fixture/login"
shutil.copytree(HERE / "theme/login", theme)
(classes / "META-INF/keycloak-themes.json").write_text(
    '{"themes":[{"name":"phone-fixture","types":["login"]}]}')
subprocess.run([str(java_bin / "jar") if java_bin else "jar", "--create", "--file",
                str(target / "probe.jar"), "-C", str(classes), "."], check=True)
for source, name in [(provider, "phone.jar"), (ROOT / "keycloak-sms-provider-dummy/target/keycloak-sms-provider-dummy-2.1.0.jar", "dummy.jar")]:
    shutil.copyfile(source, target / name)
(target / "keycloak-version.txt").write_text(args.keycloak + "\n")
print("Compiled provider and separate test-only probe; no runtime verification performed.")
