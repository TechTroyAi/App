#!/usr/bin/env python3
"""Offline APK builder for NumStrike (Mental Math Status & Secret Technique Trainer).

Packages the NumStrike app into a standalone, signed, zipaligned Android APK
(`artifacts/NumStrike-v1.0.0-installable.apk`) with package ID `ai.techtroy.numstrike`
so it can be installed side-by-side on Android devices without conflicting with
Jadex or Blockhold Defense.
"""

from __future__ import annotations

import hashlib
import os
import re
import secrets
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS_DIR = "/tmp/build-tools"
WORK_DIR = "/tmp/numstrike-build-work"

TOOLS = {
    "apksigner.jar": ("screetsec/TheFatRat", "335cc2cb9acb3244c0ac79979cb77053304d3658"),
    "apktool.jar": ("screetsec/TheFatRat", "53d723d86f2a1f58fc63d7d918a50ce7e22a08ce"),
}


def log(msg: str) -> None:
    print(f"\033[1;34m==>\033[0m \033[1m{msg}\033[0m")


def ok(msg: str) -> None:
    print(f"  \033[32mPASS\033[0m  {msg}")


def fail(msg: str) -> None:
    print(f"  \033[31mFAIL\033[0m  {msg}")
    sys.exit(1)


def find_java() -> tuple[str, str]:
    try:
        import jdk4py
        return str(jdk4py.JAVA), str(jdk4py.JAVA_HOME)
    except ImportError:
        pass
    java_bin = shutil.which("java")
    if java_bin:
        java_home = os.environ.get("JAVA_HOME", os.path.dirname(os.path.dirname(java_bin)))
        return java_bin, java_home
    fail("Java not found. Install jdk4py (`pip install --break-system-packages jdk4py`).")
    return "", ""


def ensure_tools() -> None:
    os.makedirs(TOOLS_DIR, exist_ok=True)
    for name, (repo, sha) in TOOLS.items():
        out_path = os.path.join(TOOLS_DIR, name)
        if os.path.exists(out_path) and os.path.getsize(out_path) > 1000:
            continue
        log(f"Downloading {name} from {repo} (blob {sha[:8]})...")
        url = f"https://api.github.com/repos/{repo}/git/blobs/{sha}"
        req = urllib.request.Request(
            url,
            headers={"Accept": "application/vnd.github.v3.raw", "User-Agent": "NumStrikeBuild/1.0"}
        )
        with urllib.request.urlopen(req) as resp, open(out_path, "wb") as out:
            out.write(resp.read())
        ok(f"Saved {name} ({os.path.getsize(out_path)} bytes)")


def ensure_signing_key(java_bin: str) -> tuple[str, str, str]:
    signing_dir = os.path.join(REPO, ".signing")
    os.makedirs(signing_dir, exist_ok=True)
    keystore = os.path.join(signing_dir, "numstrike-release.p12")
    props_file = os.path.join(signing_dir, "numstrike-release.properties")

    password = secrets.token_urlsafe(32)
    alias = "numstrike"

    if os.path.exists(props_file):
        with open(props_file, "r") as f:
            for line in f:
                if line.startswith("storePassword="):
                    password = line.strip().split("=", 1)[1]
                elif line.startswith("keyAlias="):
                    alias = line.strip().split("=", 1)[1]

    if not os.path.exists(keystore):
        log("Generating NumStrike release signing key...")
        keytool_bin = os.path.join(os.path.dirname(java_bin), "keytool")
        if not os.path.exists(keytool_bin):
            keytool_bin = "keytool"
        dname = "CN=NumStrike, OU=Mental Math System, O=TechTroyAi, L=Davao, ST=Davao Region, C=PH"
        cmd = [
            keytool_bin, "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "4096",
            "-sigalg", "SHA256withRSA",
            "-validity", "10950",
            "-dname", dname,
            "-keystore", keystore,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            fail(f"keytool failed: {res.stderr}")
        with open(props_file, "w") as f:
            f.write(f"storePassword={password}\nkeyAlias={alias}\nkeyPassword={password}\n")
        os.chmod(props_file, 0o600)
        os.chmod(keystore, 0o600)
        ok(f"Created keystore at {keystore}")

    return keystore, password, alias


def build_apk() -> str:
    java_bin, _ = find_java()
    ensure_tools()
    keystore, key_pass, key_alias = ensure_signing_key(java_bin)

    apksigner_jar = os.path.join(TOOLS_DIR, "apksigner.jar")
    apktool_jar = os.path.join(TOOLS_DIR, "apktool.jar")

    shutil.rmtree(WORK_DIR, ignore_errors=True)
    os.makedirs(WORK_DIR, exist_ok=True)

    base_apk = os.path.join(REPO, "artifacts", "Jadex-v1.3.7-installable.apk")
    if not os.path.exists(base_apk):
        fail(f"Base APK not found at {base_apk}")

    log("1/4 Extracting WebView host DEX and decoding APK container...")
    classes_dex = os.path.join(WORK_DIR, "classes.dex")
    with zipfile.ZipFile(base_apk, "r") as z:
        with open(classes_dex, "wb") as out_dex:
            out_dex.write(z.read("classes.dex"))

    decode_dir = os.path.join(WORK_DIR, "apk-decode")
    res = subprocess.run(
        [java_bin, "-jar", apktool_jar, "d", "-s", "-f", "-o", decode_dir, base_apk],
        capture_output=True,
        text=True,
    )
    if res.returncode != 0:
        fail(f"apktool decode failed: {res.stderr}")
    ok("Decoded base APK container")

    log("2/4 Injecting NumStrike assets & patching AndroidManifest.xml...")
    decoded_manifest = os.path.join(decode_dir, "AndroidManifest.xml")
    with open(decoded_manifest, "r", encoding="utf-8") as mf:
        man = mf.read()

    man = re.sub(r'package="[^"]+"', 'package="ai.techtroy.numstrike"', man, count=1)
    man = re.sub(r'android:label="[^"]*"', 'android:label="NumStrike"', man)
    man = re.sub(r'android:screenOrientation="[^"]*"', 'android:screenOrientation="portrait"', man)
    with open(decoded_manifest, "w", encoding="utf-8") as mf:
        mf.write(man)

    # Update strings.xml app_name to NumStrike as well
    strings_xml = os.path.join(decode_dir, "res", "values", "strings.xml")
    if os.path.exists(strings_xml):
        s_txt = open(strings_xml, "r", encoding="utf-8").read()
        s_txt = re.sub(r'<string name="app_name">[^<]*</string>', '<string name="app_name">NumStrike</string>', s_txt)
        open(strings_xml, "w", encoding="utf-8").write(s_txt)

    # Replace assets/ide and assets/math with NumStrike assets (excluding heavy pyodide)
    assets_dst = os.path.join(decode_dir, "assets")
    shutil.rmtree(assets_dst, ignore_errors=True)
    math_src = os.path.join(REPO, "app", "src", "main", "assets", "math")
    shutil.copytree(math_src, os.path.join(assets_dst, "ide"))
    shutil.copytree(math_src, os.path.join(assets_dst, "math"))
    lic_src = os.path.join(REPO, "app", "src", "main", "assets", "licenses")
    if os.path.isdir(lic_src):
        shutil.copytree(lic_src, os.path.join(assets_dst, "licenses"))

    apktool_yml = os.path.join(decode_dir, "apktool.yml")
    yml = open(apktool_yml, "r", encoding="utf-8").read()
    yml = re.sub(r"versionCode:\s*'[0-9]+'", "versionCode: '1'", yml)
    yml = re.sub(r"versionName:\s*[0-9.]+", "versionName: 1.0.0", yml)
    yml = re.sub(r"apkFileName:\s*\S+", "apkFileName: NumStrike-v1.0.0-installable.apk", yml)
    yml = re.sub(r"renameManifestPackage:\s*\S+", "renameManifestPackage: ai.techtroy.numstrike", yml)
    if "renameManifestPackage:" not in yml:
        yml += "\nrenameManifestPackage: ai.techtroy.numstrike\n"
    open(apktool_yml, "w", encoding="utf-8").write(yml)

    unaligned_apk = os.path.join(WORK_DIR, "unaligned.apk")
    res = subprocess.run(
        [java_bin, "-jar", apktool_jar, "b", decode_dir, "-o", unaligned_apk],
        capture_output=True,
        text=True,
    )
    if res.returncode != 0:
        fail(f"apktool build failed: {res.stderr}")
    ok("Rebuilt APK resources with apktool")

    log("3/4 Aligning APK (4-byte and 4096-page zipalign)...")
    aligned_apk = os.path.join(WORK_DIR, "aligned.apk")
    repackage_script = os.path.join(REPO, "scripts", "repackage-with-dex.py")
    res = subprocess.run(
        [sys.executable, repackage_script, unaligned_apk, classes_dex, aligned_apk],
        capture_output=True,
        text=True,
    )
    if res.returncode != 0:
        fail(f"repackage-with-dex failed: {res.stderr}")
    ok("Zipalign completed")

    log("4/4 Signing APK (v2 + v3 schemes) and running structural verifier...")
    final_apk = os.path.join(REPO, "artifacts", "NumStrike-v1.0.0-installable.apk")
    if os.path.exists(final_apk):
        os.remove(final_apk)

    sign_cmd = [
        java_bin, "-jar", apksigner_jar, "sign",
        "--ks", keystore,
        "--ks-type", "PKCS12",
        "--ks-key-alias", key_alias,
        "--ks-pass", f"pass:{key_pass}",
        "--min-sdk-version", "26",
        "--v1-signing-enabled", "false",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--in", aligned_apk,
        "--out", final_apk,
    ]
    res = subprocess.run(sign_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        fail(f"apksigner failed: {res.stderr}")

    subprocess.run(
        [java_bin, "-jar", apksigner_jar, "verify", "--verbose", "--print-certs", final_apk],
        check=True,
    )

    verify_script = os.path.join(REPO, "scripts", "verify-apk.py")
    res = subprocess.run([sys.executable, verify_script, final_apk], capture_output=True, text=True)
    print(res.stdout)
    if res.returncode != 0:
        fail("APK verification failed")

    sha256 = hashlib.sha256(open(final_apk, "rb").read()).hexdigest()
    size = os.path.getsize(final_apk)
    log("SUCCESS: NumStrike v1.0.0 Android APK is ready!")
    print(f"  Artifact: {final_apk}")
    print(f"  Size:     {size:,} bytes ({size / (1024 * 1024):.2f} MiB)")
    print(f"  SHA-256:  {sha256}")
    return final_apk


if __name__ == "__main__":
    build_apk()
