#!/usr/bin/env python3
"""Verify a staged Cloudy APK and write release/build metadata.

The helper deliberately inspects only the final file that will be uploaded.
It does not invoke Gradle and never handles signing passwords.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

FINGERPRINT = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)")
PACKAGE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'")
SDK = re.compile(r"(?:minSdkVersion|sdkVersion):'([0-9]+)'")
TARGET_SDK = re.compile(r"targetSdkVersion:'([0-9]+)'")


def fail(message: str) -> None:
    print(f"APK verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def run(*args: str) -> str:
    proc = subprocess.run(args, capture_output=True, text=True)
    if proc.returncode != 0:
        fail(f"{' '.join(args)} failed:\n{proc.stdout}\n{proc.stderr}")
    return proc.stdout


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--app-name", required=True)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--metadata-out", required=True, type=Path)
    parser.add_argument("--meta", action="append", default=[],
                        help="Additional metadata as KEY=VALUE")
    parser.add_argument("--expected-abi", action="append", default=[],
                        help="Allowed ABI; repeat for multiple")
    parser.add_argument("--require-native-lib", default=None,
                        help="Exact ZIP path required in the APK")
    parser.add_argument("--require-asset", action="append", default=[],
                        help="Exact assets/... ZIP path required")
    parser.add_argument("--expected-cert-sha256", default="",
                        help="Optional expected signer SHA-256 digest")
    args = parser.parse_args()

    apk = args.apk
    if not apk.is_file() or apk.stat().st_size <= 0:
        fail(f"missing or empty APK: {apk}")

    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        infos = {info.filename: info for info in archive.infolist()}
        for required in args.require_asset:
            if required not in names or infos[required].file_size <= 0:
                fail(f"required asset is missing/empty: {required}")
        if args.require_native_lib:
            if args.require_native_lib not in names:
                fail(f"required native library is missing: {args.require_native_lib}")
            if infos[args.require_native_lib].file_size <= 0:
                fail(f"required native library is empty: {args.require_native_lib}")
        abis = sorted({name.split("/")[1] for name in names
                       if name.startswith("lib/") and name.count("/") >= 2})
        native_libs = sorted(name for name in names if name.startswith("lib/")
                             and name.endswith(".so"))
    if args.expected_abi:
        unexpected = set(abis) - set(args.expected_abi)
        if unexpected:
            fail(f"unexpected packaged ABIs: {sorted(unexpected)}; allowed {args.expected_abi}")
        missing = set(args.expected_abi) - set(abis)
        if missing:
            fail(f"expected ABIs missing: {sorted(missing)}; actual {abis}")

    badging = run(args.aapt2, "dump", "badging", str(apk))
    package_match = PACKAGE.search(badging)
    min_match = SDK.search(badging)
    target_match = TARGET_SDK.search(badging)
    if not package_match:
        fail("aapt2 did not return package/version fields")
    package, version_code, version_name = package_match.groups()
    if package != args.package:
        fail(f"application id mismatch: expected {args.package}, got {package}")
    min_sdk = int(min_match.group(1)) if min_match else None
    target_sdk = int(target_match.group(1)) if target_match else None
    if min_sdk is None or target_sdk is None:
        fail("aapt2 did not return min/target SDK")

    signature = run(args.apksigner, "verify", "--verbose", "--print-certs", str(apk))
    fingerprint_match = FINGERPRINT.search(signature)
    if not fingerprint_match:
        fail("apksigner did not return a signer certificate")
    fingerprint = fingerprint_match.group(1).replace(":", "").lower()
    expected = args.expected_cert_sha256.replace(":", "").lower()
    if expected and fingerprint != expected:
        fail(f"signer mismatch: expected {expected}, got {fingerprint}")

    additional = {}
    for item in args.meta:
        if "=" not in item:
            fail(f"metadata item must be KEY=VALUE: {item!r}")
        key, value = item.split("=", 1)
        if not key:
            fail("empty metadata key")
        additional[key] = value

    digest = sha256(apk)
    metadata = {
        **additional,
        "app_name": args.app_name,
        "apk_file": apk.name,
        "apk_size_bytes": apk.stat().st_size,
        "apk_sha256": digest,
        "application_id": package,
        "variant": args.variant,
        "mode": args.mode,
        "version_code": version_code,
        "version_name": version_name,
        "min_sdk": min_sdk,
        "target_sdk": target_sdk,
        "abis": abis,
        "native_libs": native_libs,
        "signature_valid": True,
        "signing_certificate_sha256": fingerprint,
        "verified_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    args.metadata_out.parent.mkdir(parents=True, exist_ok=True)
    args.metadata_out.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n",
                                 encoding="utf-8")
    print(f"verified {apk} ({package}, version {version_name}/{version_code}, "
          f"SDK {min_sdk}-{target_sdk}, ABIs {abis or 'none'})")
    print(f"SHA256 {digest}")
    print(f"signer {fingerprint}")


if __name__ == "__main__":
    main()
