#!/usr/bin/env python3
"""Check the actual APK signature before a Relay Lab release."""
import argparse
import re
import subprocess
from pathlib import Path

EXPECTED = "0b569d33543efb55c13c87bff6041944118dfff00c9a350e96776712e22347b0"

def certificate(apksigner, apk):
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    )
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", result.stdout)
    if len(digests) != 1:
        raise SystemExit("Expected one APK signer.")
    return digests[0]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apksigner", required=True)
    args = parser.parse_args()
    actual = certificate(args.apksigner, args.apk)
    if actual != EXPECTED:
        raise SystemExit(
            "BLOCKED: APK signer differs from persistent Relay Lab RC58 identity. "
            "Restore the saved RC58 signing backup before publishing an update. "
            f"Expected {EXPECTED}; received {actual}."
        )
    print("PASS: verified APK signature matches the established Relay Lab certificate.")

if __name__ == "__main__":
    main()
