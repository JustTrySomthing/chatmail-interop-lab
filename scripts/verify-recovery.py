#!/usr/bin/env python3
"""Verify every file in an extracted recovery checkpoint before editing."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'RECOVERY-FILES.json').read_text())
failures = []
for name, expected in manifest['sha256'].items():
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        failures.append(name)
if failures:
    raise SystemExit('FAIL: missing or changed checkpoint files: ' + ', '.join(failures))
print(f"PASS: {len(manifest['sha256'])} checkpoint files verified")
