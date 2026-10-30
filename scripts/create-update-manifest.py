"""Create public updater metadata for a signed release APK.

Usage: create-update-manifest.py <apk> <manifest name> <package name>
"""
import hashlib
import json
import re
import sys
from pathlib import Path

apk = Path(sys.argv[1])
manifest_name = sys.argv[2] if len(sys.argv) > 2 else "update.json"
source = Path("app/build.gradle.kts").read_text()
package = sys.argv[3] if len(sys.argv) > 3 else re.search(r'applicationId\s*=\s*"([^"]+)"', source).group(1)
metadata = {
    "schema": 1,
    "packageName": package,
    "versionCode": int(re.search(r'versionCode\s*=\s*(\d+)', source).group(1)),
    "versionName": re.search(r'versionName\s*=\s*"([^"]+)"', source).group(1),
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
}
apk.with_name(manifest_name).write_text(json.dumps(metadata, indent=2) + "\n")
