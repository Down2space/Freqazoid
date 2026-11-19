#!/usr/bin/env python3
"""Download verified Gradle 8.9 and build using an installed Android SDK + JDK 17+."""
import hashlib, os, pathlib, subprocess, urllib.request, zipfile
root = pathlib.Path(__file__).resolve().parent
cache = root / '.build-tools'
cache.mkdir(exist_ok=True)
gradle = cache / 'gradle-8.9'
if not gradle.exists():
    url = 'https://services.gradle.org/distributions/gradle-8.9-bin.zip'
    archive = cache / 'gradle.zip'
    print('Downloading Gradle 8.9 (first run only)...', flush=True)
    urllib.request.urlretrieve(url, archive)
    expected = urllib.request.urlopen(url + '.sha256').read().decode().strip().split()[0]
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != expected:
        archive.unlink()
        raise SystemExit('Gradle checksum mismatch; stopped.')
    with zipfile.ZipFile(archive) as z:
        z.extractall(cache)
    archive.unlink()
exe = gradle / 'bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
if os.name != 'nt':
    exe.chmod(0o755)
subprocess.run([str(exe), '--no-daemon', 'assembleDebug'], cwd=root, check=True)
print('APK: app/build/outputs/apk/debug/app-debug.apk')

