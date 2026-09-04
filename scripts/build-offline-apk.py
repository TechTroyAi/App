#!/usr/bin/env python3
"""Offline APK builder for Blockhold Defense.

Builds a fully functional, signed release APK using the offline toolchain:
  1. Compiles Kotlin sources with kotlinc (class-based lambdas for API 24+ compatibility)
  2. Dexes application bytecode + kotlin-stdlib + annotations with dx (format 038)
  3. Packages and links updated resources with apktool (aapt2)
  4. Applies 4-byte and 4096-page zipalign semantics
  5. Signs with apksigner (v2 + v3 scheme) using the PKCS12 release key
  6. Runs comprehensive structural and dex integrity verification

Usage:
  python3 scripts/build-offline-apk.py
"""

from __future__ import annotations

import glob
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS_DIR = "/tmp/build-tools"
WORK_DIR = "/tmp/build-work"

TOOLS = {
    "android.jar": ("Sable/android-platforms", "2d6652edf0fb6046a23638f3a2f2c6246c745b08"),
    "dx.jar": ("screetsec/TheFatRat", "ef90a14d8f2a6deda26da6ca50682e3384e74dfe"),
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

    fail("Java not found. Please install jdk4py or JDK 17+.")
    return "", ""


def find_kotlinc() -> str:
    candidates = [
        shutil.which("kotlinc"),
        "/usr/local/lib/node_modules/kotlin-compiler/bin/kotlinc",
        os.path.expanduser("~/.npm-global/bin/kotlinc"),
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    fail("kotlinc not found. Please install kotlin-compiler (e.g. npm install -g kotlin-compiler@1.9.25).")
    return ""


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
            headers={"Accept": "application/vnd.github.v3.raw", "User-Agent": "BlockholdBuild/1.0"}
        )
        try:
            with urllib.request.urlopen(req) as resp, open(out_path, "wb") as out:
                out.write(resp.read())
            ok(f"Saved {name} ({os.path.getsize(out_path)} bytes)")
        except Exception as e:
            fail(f"Failed to fetch {name}: {e}")


def ensure_signing_key(java_bin: str) -> tuple[str, str, str]:
    signing_dir = os.path.join(REPO, ".signing")
    os.makedirs(signing_dir, exist_ok=True)
    keystore = os.path.join(signing_dir, "blockhold-release.p12")
    props_file = os.path.join(signing_dir, "release.properties")

    password = "blockholdreleasekey"
    alias = "blockhold"

    if os.path.exists(props_file):
        with open(props_file, "r") as f:
            for line in f:
                if line.startswith("storePassword="):
                    password = line.strip().split("=", 1)[1]
                elif line.startswith("keyAlias="):
                    alias = line.strip().split("=", 1)[1]

    if not os.path.exists(keystore):
        log("Generating release signing key...")
        keytool_bin = os.path.join(os.path.dirname(java_bin), "keytool")
        if not os.path.exists(keytool_bin):
            keytool_bin = "keytool"

        dname = "CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH"
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
        ok(f"Created release keystore at {keystore}")

    return keystore, password, alias


def find_stdlib_jars() -> tuple[str, str]:
    kotlinc_dir = "/usr/local/lib/node_modules/kotlin-compiler"
    stdlib = os.path.join(kotlinc_dir, "lib", "kotlin-stdlib.jar")
    annot = os.path.join(kotlinc_dir, "lib", "annotations-13.0.jar")
    if os.path.exists(stdlib) and os.path.exists(annot):
        return stdlib, annot

    # Search in common node_modules or system paths
    for base in ["/usr/local/lib/node_modules", "/usr/lib/node_modules", os.path.expanduser("~/.npm-global/lib/node_modules")]:
        s = os.path.join(base, "kotlin-compiler", "lib", "kotlin-stdlib.jar")
        a = os.path.join(base, "kotlin-compiler", "lib", "annotations-13.0.jar")
        if os.path.exists(s) and os.path.exists(a):
            return s, a

    fail("kotlin-stdlib.jar or annotations-13.0.jar not found.")
    return "", ""


def build_apk() -> str:
    java_bin, java_home = find_java()
    kotlinc_bin = find_kotlinc()
    stdlib_jar, annot_jar = find_stdlib_jars()
    ensure_tools()
    keystore, key_pass, key_alias = ensure_signing_key(java_bin)

    android_jar = os.path.join(TOOLS_DIR, "android.jar")
    dx_jar = os.path.join(TOOLS_DIR, "dx.jar")
    apksigner_jar = os.path.join(TOOLS_DIR, "apksigner.jar")
    apktool_jar = os.path.join(TOOLS_DIR, "apktool.jar")

    shutil.rmtree(WORK_DIR, ignore_errors=True)
    os.makedirs(WORK_DIR, exist_ok=True)

    # 1. Compile Kotlin
    log("1/6 Compiling Kotlin source files with kotlinc...")
    classes_dir = os.path.join(WORK_DIR, "classes")
    os.makedirs(classes_dir, exist_ok=True)

    src_files = sorted(glob.glob(os.path.join(REPO, "app", "src", "main", "java", "ai", "techtroy", "blockhold", "*.kt")))
    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    env["PATH"] = f"{os.path.dirname(java_bin)}:{env.get('PATH', '')}"

    kotlinc_cmd = [
        kotlinc_bin,
        # -no-jdk: the app's Java types come from android.jar, and Kotlin 1.9.25's
        # bundled JavaVersion probe cannot parse modern runtimes (e.g. Temurin 25
        # from current jdk4py wheels), aborting the compile before it starts.
        "-no-jdk",
        "-jvm-target", "1.8",
        "-Xlambdas=class",
        "-Xsam-conversions=class",
        "-J-Xmx2600m",
        "-classpath", android_jar,
        "-d", classes_dir,
    ] + src_files

    res = subprocess.run(kotlinc_cmd, env=env, capture_output=True, text=True)
    if res.returncode != 0:
        print("kotlinc stdout:", res.stdout)
        print("kotlinc stderr:", res.stderr)
        fail(f"kotlinc failed with exit code {res.returncode}")
    ok("Compiled Kotlin sources successfully")

    # 2. Extract stdlib and merge
    log("2/6 Merging Kotlin runtime classes for dexing...")
    dex_classes_dir = os.path.join(WORK_DIR, "dex-classes")
    shutil.copytree(classes_dir, dex_classes_dir)

    for jar_path in [stdlib_jar, annot_jar]:
        with zipfile.ZipFile(jar_path, "r") as z:
            for member in z.infolist():
                if (
                    member.filename.endswith(".class")
                    and "module-info" not in member.filename
                    and not member.filename.startswith("META-INF")
                ):
                    z.extract(member, dex_classes_dir)

    # Clean any accidental module-info or META-INF
    for root, dirs, files in os.walk(dex_classes_dir):
        for f in files:
            if "module-info" in f:
                os.remove(os.path.join(root, f))
        if "META-INF" in dirs:
            shutil.rmtree(os.path.join(root, "META-INF"), ignore_errors=True)

    class_count = sum(len([f for f in files if f.endswith(".class")]) for _, _, files in os.walk(dex_classes_dir))
    ok(f"Prepared {class_count} class files")

    # 3. Dex with dx
    log("3/6 Compiling Dalvik bytecode with dx...")
    classes_dex = os.path.join(WORK_DIR, "classes.dex")
    dx_cmd = [
        java_bin, "-jar", dx_jar,
        "--dex",
        "--min-sdk-version=26",
        f"--output={classes_dex}",
        dex_classes_dir,
    ]
    res = subprocess.run(dx_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("dx stderr:", res.stderr)
        fail(f"dx failed with exit code {res.returncode}")
    ok(f"Generated classes.dex ({os.path.getsize(classes_dex)} bytes)")

    # 4. Decode base APK and package resources with apktool
    log("4/6 Linking and packaging resources with apktool...")
    base_apk = os.path.join(REPO, "artifacts", "Blockhold-Defense-v1.4.3-installable.apk")
    if not os.path.exists(base_apk):
        base_apks = sorted(glob.glob(os.path.join(REPO, "artifacts", "*.apk")))
        if not base_apks:
            fail("No base APK found in artifacts/")
        base_apk = base_apks[-1]

    decode_dir = os.path.join(WORK_DIR, "apk-decode")
    decode_cmd = [java_bin, "-jar", apktool_jar, "d", "-s", "-f", "-o", decode_dir, base_apk]
    res = subprocess.run(decode_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        fail(f"apktool decode failed: {res.stderr}")

    # Copy new classes.dex
    shutil.copy2(classes_dex, os.path.join(decode_dir, "classes.dex"))

    # Copy current res/ files
    res_src_dir = os.path.join(REPO, "app", "src", "main", "res")
    for root, dirs, files in os.walk(res_src_dir):
        rel_path = os.path.relpath(root, res_src_dir)
        target_dir = os.path.join(decode_dir, "res", rel_path)
        os.makedirs(target_dir, exist_ok=True)
        for f in files:
            shutil.copy2(os.path.join(root, f), os.path.join(target_dir, f))

    # Update apktool.yml
    apktool_yml = os.path.join(decode_dir, "apktool.yml")
    with open(apktool_yml, "r") as f:
        yml = f.read()

    # Read current version from app/build.gradle.kts
    gradle_kts = open(os.path.join(REPO, "app", "build.gradle.kts")).read()
    version_code = "18"
    version_name = "1.4.4"
    import re
    vc_match = re.search(r'versionCode\s*=\s*(\d+)', gradle_kts)
    if vc_match:
        version_code = vc_match.group(1)
    vn_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_kts)
    if vn_match:
        version_name = vn_match.group(1)

    yml = re.sub(r"versionCode:\s*'[0-9]+'", f"versionCode: '{version_code}'", yml)
    yml = re.sub(r"versionName:\s*[0-9.]+", f"versionName: {version_name}", yml)
    yml = re.sub(r"apkFileName:\s*\S+", f"apkFileName: Blockhold-Defense-v{version_name}-installable.apk", yml)

    with open(apktool_yml, "w") as f:
        f.write(yml)

    unaligned_apk = os.path.join(WORK_DIR, "unaligned.apk")
    build_cmd = [java_bin, "-jar", apktool_jar, "b", decode_dir, "-o", unaligned_apk]
    res = subprocess.run(build_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("apktool build stderr:", res.stderr)
        fail(f"apktool build failed with exit code {res.returncode}")
    ok("Packaged resources and rebuilt unaligned APK")

    # 5. Repackage & Align
    log("5/6 Aligning APK layout (4-byte and 4096-page alignment)...")
    aligned_apk = os.path.join(WORK_DIR, "aligned.apk")
    repackage_script = os.path.join(REPO, "scripts", "repackage-with-dex.py")
    res = subprocess.run([sys.executable, repackage_script, unaligned_apk, classes_dex, aligned_apk], capture_output=True, text=True)
    if res.returncode != 0:
        print("repackage stderr:", res.stderr)
        fail(f"repackage-with-dex failed with exit code {res.returncode}")
    ok("Zipalign completed successfully")

    # 6. Sign with apksigner
    log("6/6 Signing APK with release key (v2 + v3 schemes)...")
    final_apk = os.path.join(REPO, "artifacts", f"Blockhold-Defense-v{version_name}-installable.apk")
    if os.path.exists(final_apk):
        os.remove(final_apk)

    sign_cmd = [
        java_bin, "-jar", apksigner_jar, "sign",
        "--ks", keystore,
        "--ks-type", "PKCS12",
        "--ks-key-alias", key_alias,
        "--ks-pass", f"pass:{key_pass}",
        "--min-sdk-version", "24",
        "--v1-signing-enabled", "false",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--in", aligned_apk,
        "--out", final_apk,
    ]
    res = subprocess.run(sign_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("apksigner stderr:", res.stderr)
        fail(f"apksigner failed with exit code {res.returncode}")
    ok(f"Signed APK produced at {final_apk}")

    # Verification
    log("Running static verifications...")
    verify_script = os.path.join(REPO, "scripts", "verify-apk.py")
    res = subprocess.run([sys.executable, verify_script, final_apk], capture_output=True, text=True)
    print(res.stdout)
    if res.returncode != 0:
        fail("verify-apk check failed")

    verify_dex_script = os.path.join(REPO, "scripts", "verify-dex-shape.py")
    res = subprocess.run([sys.executable, verify_dex_script, classes_dex], capture_output=True, text=True)
    print(res.stdout)
    if res.returncode != 0:
        fail("verify-dex-shape check failed")

    sha256 = hashlib.sha256(open(final_apk, "rb").read()).hexdigest()
    size = os.path.getsize(final_apk)
    log(f"SUCCESS: Blockhold Defense v{version_name} APK is ready!")
    print(f"  Artifact: {final_apk}")
    print(f"  Size:     {size:,} bytes")
    print(f"  SHA-256:  {sha256}")

    return final_apk


if __name__ == "__main__":
    build_apk()
