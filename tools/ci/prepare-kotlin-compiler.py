#!/usr/bin/env python3
"""Populate Gradle's portable artifact cache using either Maven Central host."""

import hashlib
import os
from pathlib import Path
import re
import tempfile
import urllib.request


version = re.search(r'^kotlin\s*=\s*"([^"/]+)"', Path("gradle/libs.versions.toml").read_text(), re.MULTILINE)[1]
artifact = f"kotlin-compiler-embeddable-{version}.jar"
relative = f"org/jetbrains/kotlin/kotlin-compiler-embeddable/{version}/{artifact}"
gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
cache = gradle_home / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable" / version

for host in ("repo.maven.apache.org", "repo1.maven.org"):
    url = f"https://{host}/maven2/{relative}"
    try:
        with urllib.request.urlopen(url + ".sha1", timeout=30) as response:
            checksum = response.read(1024).decode().strip().split()[0].lower()
        if len(checksum) != 40 or any(c not in "0123456789abcdef" for c in checksum):
            raise ValueError("Invalid Maven checksum")
        destination = cache / checksum / artifact
        if destination.exists() and hashlib.sha1(destination.read_bytes()).hexdigest() == checksum:
            print(f"Verified cached Kotlin compiler {version}")
            break
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=destination.parent) as staging:
            downloaded = Path(staging) / artifact
            digest = hashlib.sha1()
            with urllib.request.urlopen(url, timeout=60) as response, downloaded.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
                    digest.update(chunk)
            if digest.hexdigest() != checksum:
                raise ValueError("Kotlin compiler checksum mismatch")
            downloaded.replace(destination)
        print(f"Downloaded and verified Kotlin compiler {version} from {host}")
        break
    except Exception as error:
        print(f"Kotlin compiler download from {host} failed: {error}")
else:
    raise SystemExit("Neither Maven Central host supplied a verified Kotlin compiler")
