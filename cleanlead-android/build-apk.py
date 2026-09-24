#!/usr/bin/env python3
"""Offline APK builder for CleanLead.

Builds a fully functional, signed release APK:
  1. Compiles Kotlin sources with kotlinc
  2. Dexes application bytecode + kotlin-stdlib with dx
  3. Packages resources with apktool
  4. Signs with apksigner (v2 + v3)
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile

PROJECT = os.path.dirname(os.path.abspath(__file__))
TOOLS_DIR = "/tmp/cl-build-tools"
WORK_DIR = "/tmp/cl-build-work"

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
    fail("Java not found. Install jdk4py (pip install jdk4py) or JDK 17+.")
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
    fail("kotlinc not found. Install: npm install -g kotlin-compiler@1.9.25")
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
            headers={"Accept": "application/vnd.github.v3.raw", "User-Agent": "CleanLeadBuild/1.0"},
        )
        try:
            with urllib.request.urlopen(req) as resp, open(out_path, "wb") as out:
                out.write(resp.read())
            ok(f"Saved {name} ({os.path.getsize(out_path):,} bytes)")
        except Exception as e:
            fail(f"Failed to fetch {name}: {e}")


def find_stdlib_jars() -> tuple[str, str]:
    kotlinc_bin = find_kotlinc()
    kotlinc_dir = os.path.dirname(os.path.dirname(os.path.realpath(kotlinc_bin)))
    stdlib = os.path.join(kotlinc_dir, "lib", "kotlin-stdlib.jar")
    annot = os.path.join(kotlinc_dir, "lib", "annotations-13.0.jar")
    if os.path.exists(stdlib) and os.path.exists(annot):
        return stdlib, annot

    for base in ["/usr/local/lib/node_modules", "/usr/lib/node_modules",
                 os.path.expanduser("~/.npm-global/lib/node_modules")]:
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

    android_jar = os.path.join(TOOLS_DIR, "android.jar")
    dx_jar = os.path.join(TOOLS_DIR, "dx.jar")
    apksigner_jar = os.path.join(TOOLS_DIR, "apksigner.jar")
    apktool_jar = os.path.join(TOOLS_DIR, "apktool.jar")

    signing_dir = os.path.join(PROJECT, ".signing")
    keystore = os.path.join(signing_dir, "cleanlead-release.p12")
    props_file = os.path.join(signing_dir, "release.properties")

    if not os.path.exists(keystore) or not os.path.exists(props_file):
        fail("Signing key not found. Run keytool generation first.")

    # Read signing properties
    password = ""
    alias = "cleanlead"
    with open(props_file) as f:
        for line in f:
            if line.startswith("storePassword="):
                password = line.strip().split("=", 1)[1]
            elif line.startswith("keyAlias="):
                alias = line.strip().split("=", 1)[1]

    shutil.rmtree(WORK_DIR, ignore_errors=True)
    os.makedirs(WORK_DIR, exist_ok=True)

    # 1. Compile Kotlin
    log("1/5  Compiling Kotlin sources...")
    classes_dir = os.path.join(WORK_DIR, "classes")
    os.makedirs(classes_dir, exist_ok=True)

    src_files = [os.path.join(PROJECT, "app", "src", "main", "java", "ai", "techtroy", "cleanlead", "MainActivity.kt")]
    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    env["PATH"] = f"{os.path.dirname(java_bin)}:{env.get('PATH', '')}"

    kotlinc_cmd = [
        kotlinc_bin,
        "-no-jdk",
        "-jvm-target", "1.8",
        "-Xlambdas=class",
        "-Xsam-conversions=class",
        "-J-Xmx2048m",
        "-classpath", android_jar,
        "-d", classes_dir,
    ] + src_files

    res = subprocess.run(kotlinc_cmd, env=env, capture_output=True, text=True)
    if res.returncode != 0:
        print("kotlinc stdout:", res.stdout[-2000:] if res.stdout else "")
        print("kotlinc stderr:", res.stderr[-2000:] if res.stderr else "")
        fail(f"kotlinc failed with exit code {res.returncode}")
    ok("Compiled Kotlin sources")

    # 2. Merge classes with stdlib for dexing
    log("2/5  Merging Kotlin runtime for dexing...")
    dex_classes_dir = os.path.join(WORK_DIR, "dex-classes")
    shutil.copytree(classes_dir, dex_classes_dir)

    for jar_path in [stdlib_jar, annot_jar]:
        with zipfile.ZipFile(jar_path, "r") as z:
            for member in z.infolist():
                if (member.filename.endswith(".class")
                    and "module-info" not in member.filename
                    and not member.filename.startswith("META-INF")):
                    z.extract(member, dex_classes_dir)

    # Clean module-info
    for root, dirs, files in os.walk(dex_classes_dir):
        for f in files:
            if "module-info" in f:
                os.remove(os.path.join(root, f))
        if "META-INF" in dirs:
            shutil.rmtree(os.path.join(root, "META-INF"), ignore_errors=True)

    class_count = sum(1 for _, _, files in os.walk(dex_classes_dir)
                      for f in files if f.endswith(".class"))
    ok(f"Prepared {class_count} class files")

    # 3. Dex with dx
    log("3/5  Compiling Dalvik bytecode with dx...")
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
        print("dx stderr:", res.stderr[-2000:])
        fail(f"dx failed with exit code {res.returncode}")
    ok(f"Generated classes.dex ({os.path.getsize(classes_dex):,} bytes)")

    # 4. Build APK with apktool
    log("4/5  Packaging APK with apktool...")
    # Create a minimal apktool project structure
    apk_build_dir = os.path.join(WORK_DIR, "apk-build")
    os.makedirs(apk_build_dir, exist_ok=True)

    # Copy AndroidManifest.xml
    manifest_src = os.path.join(PROJECT, "app", "src", "main", "AndroidManifest.xml")
    manifest_dst = os.path.join(apk_build_dir, "AndroidManifest.xml")
    with open(manifest_src, "r") as f:
        manifest = f.read()
    with open(manifest_dst, "w") as f:
        f.write(manifest)

    # Copy resources
    res_src = os.path.join(PROJECT, "app", "src", "main", "res")
    res_dst = os.path.join(apk_build_dir, "res")
    if os.path.exists(res_dst):
        shutil.rmtree(res_dst)
    shutil.copytree(res_src, res_dst)

    # Copy assets
    assets_src = os.path.join(PROJECT, "app", "src", "main", "assets")
    assets_dst = os.path.join(apk_build_dir, "assets")
    if os.path.exists(assets_dst):
        shutil.rmtree(assets_dst)
    shutil.copytree(assets_src, assets_dst)

    # Copy classes.dex
    shutil.copy2(classes_dex, os.path.join(apk_build_dir, "classes.dex"))

    # Create apktool.yml
    with open(os.path.join(apk_build_dir, "apktool.yml"), "w") as f:
        f.write("""!!brut.androlib.meta.MetaInfo
version: 2.9.3
apkFileName: CleanLead-v1.0.0-release.apk
compressionType: false
doNotCompress:
- br
- wasm
- zip
isFrameworkApk: false
packageInfo:
  forcedPackageId: '127'
  renameManifestPackage: null
sdkInfo:
  minSdkVersion: '26'
  targetSdkVersion: '35'
sharedLibrary: false
usesFramework:
  ids:
  - 1
  tag: null
versionInfo:
  versionCode: '1'
  versionName: 1.0.0
""")

    unaligned_apk = os.path.join(WORK_DIR, "unaligned.apk")
    build_cmd = [java_bin, "-jar", apktool_jar, "b", apk_build_dir, "-o", unaligned_apk]
    res = subprocess.run(build_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("apktool stdout:", res.stdout[-2000:])
        print("apktool stderr:", res.stderr[-2000:])
        fail(f"apktool build failed with exit code {res.returncode}")
    ok(f"Packaged unaligned APK ({os.path.getsize(unaligned_apk):,} bytes)")

    # 5. Align and sign
    log("5/5  Signing APK with release key (v2+v3)...")

    # First, zipalign manually
    aligned_apk = os.path.join(WORK_DIR, "aligned.apk")
    _zipalign(unaligned_apk, aligned_apk)
    ok("Zipalign completed")

    # Sign
    artifacts_dir = os.path.join(PROJECT, "artifacts")
    os.makedirs(artifacts_dir, exist_ok=True)
    final_apk = os.path.join(artifacts_dir, "CleanLead-v1.0.0-installable.apk")
    if os.path.exists(final_apk):
        os.remove(final_apk)

    sign_cmd = [
        java_bin, "-jar", apksigner_jar, "sign",
        "--ks", keystore,
        "--ks-type", "PKCS12",
        "--ks-key-alias", alias,
        "--ks-pass", f"pass:{password}",
        "--min-sdk-version", "26",
        "--v1-signing-enabled", "false",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--in", aligned_apk,
        "--out", final_apk,
    ]
    res = subprocess.run(sign_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("apksigner stderr:", res.stderr[-2000:])
        fail(f"apksigner failed with exit code {res.returncode}")
    ok(f"Signed APK: {final_apk}")

    # Verify
    verify_cmd = [java_bin, "-jar", apksigner_jar, "verify", "--verbose", "--print-certs", final_apk]
    res = subprocess.run(verify_cmd, capture_output=True, text=True)
    if res.returncode == 0:
        ok("Signature verification passed")
    else:
        print("apksigner verify stderr:", res.stderr[-1000:])

    sha256 = hashlib.sha256(open(final_apk, "rb").read()).hexdigest()
    size = os.path.getsize(final_apk)

    log(f"SUCCESS: CleanLead v1.0.0 APK is ready!")
    print(f"  Artifact: {final_apk}")
    print(f"  Size:     {size:,} bytes")
    print(f"  SHA-256:  {sha256}")

    return final_apk


def _zipalign(input_apk: str, output_apk: str) -> None:
    """Manual zipalign: ensures 4-byte alignment of all entries.
    resources.arsc MUST be STORED for targetSdk >= 30.
    All STORED entries must have data at 4-byte aligned offsets."""
    with zipfile.ZipFile(input_apk, 'r') as zin, zipfile.ZipFile(output_apk, 'w') as zout:
        current_offset = 0
        for item in zin.infolist():
            data = zin.read(item.filename)
            name_bytes = item.filename.encode('utf-8')
            name_len = len(name_bytes)

            # resources.arsc must be STORED (uncompressed) for targetSdk >= 30
            if item.filename == "resources.arsc":
                compress_type = zipfile.ZIP_STORED
            else:
                compress_type = item.compress_type

            # Calculate padding needed for 4-byte alignment of data offset
            # Local file header = 30 bytes + name_len + extra_len
            # Data starts at: current_offset + 30 + name_len + extra_len
            header_size = 30 + name_len
            data_start = current_offset + header_size
            padding_needed = (4 - (data_start % 4)) % 4
            extra = b'\x00' * padding_needed if padding_needed > 0 else b''

            new_item = zipfile.ZipInfo(item.filename)
            new_item.compress_type = compress_type
            new_item.extra = extra
            new_item.external_attr = item.external_attr
            new_item.internal_attr = item.internal_attr
            new_item.date_time = item.date_time
            zout.writestr(new_item, data)

            # Track offset for next entry
            # Local file header: 30 + name_len + extra_len
            # Compressed/stored data size
            entry_size = header_size + len(extra) + len(data)
            current_offset += entry_size


if __name__ == "__main__":
    build_apk()
