#!/usr/bin/env python3
"""Build PureEQ's GitHub flavour and publish it as an over-the-air update on GitHub Releases.

Installed copies read https://github.com/<repo>/releases/latest/download/update.json when the app
opens, every 30 minutes while the phone charges on Wi-Fi, and once a day otherwise. When that
notice announces a higher versionCode, the phone downloads the APK, checks its size, SHA-256,
package, version and signing key, and installs it.

    python3 ota/publish.py                         build, test, publish (the first run sets up the repo)
    python3 ota/publish.py --notes "What changed"  release notes shown on the phone
    python3 ota/publish.py --dry-run               build and write ota/out/update.json; publish nothing

Needs the GitHub CLI, logged in to the account that hosts the updates (brew install gh; gh auth login).
Android only installs an update signed with the same key as the copy on the phone, or with a newer
key whose lineage includes it. Gradle signs with the release key from keystore.properties or the
PUREEQ_* variables, and without one with this computer's debug key; with a lineage configured, the
APK is re-signed with it. Nothing is published unless the APK is signed so that phones with the
first and the latest APK in dist/ accept it. See docs/key-rotation.md.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = ROOT / "version.properties"
OTA_FILE = ROOT / "ota.properties"
CHANGELOG = ROOT / "CHANGELOG.md"
OUT = ROOT / "ota" / "out"
DIST = ROOT / "dist"
BUILT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "github" / "release" / "app-github-release.apk"
KEYSTORE_PROPS = ROOT / "keystore.properties"
# keystore.properties names and the environment variables that replace that file, as in app/build.gradle.kts.
SIGNING_NAMES = {
    "storeFile": "PUREEQ_KEYSTORE",
    "storePassword": "PUREEQ_KEYSTORE_PASSWORD",
    "keyAlias": "PUREEQ_KEY_ALIAS",
    "keyPassword": "PUREEQ_KEY_PASSWORD",
    "lineage": "PUREEQ_LINEAGE",
}
APP_ID = "app.pureeq"
REPO_RE = re.compile(r"^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")


def die(msg):
    print(f"\n{msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd):
    subprocess.run(cmd, cwd=ROOT, check=True)


def output(cmd):
    return subprocess.run(cmd, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def read_props(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip()
    return values


def write_props(path, values, comment):
    body = "".join(f"{k}={v}\n" for k, v in values.items())
    path.write_text(f"# {comment}\n{body}")


def manifest_url(repo):
    return f"https://github.com/{repo}/releases/latest/download/update.json"


def published(repo):
    """(versionCode, versionName) phones currently see, or None when nothing is published."""
    req = urllib.request.Request(manifest_url(repo), headers={"Cache-Control": "no-cache", "User-Agent": "pureeq-publish"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    return int(data["versionCode"]), str(data["versionName"])


def version_key(name):
    return tuple(int(x) for x in re.findall(r"\d+", name))


def bump_patch(name):
    parts = (list(version_key(name)) + [0, 0, 0])[:3]
    parts[2] += 1
    return ".".join(map(str, parts))


def changelog_notes(version):
    if not CHANGELOG.exists():
        return None
    m = re.search(rf"^## {re.escape(version)}[^\n]*\n(.*?)(?=^## |\Z)", CHANGELOG.read_text(), re.S | re.M)
    return m.group(1).strip() if m and m.group(1).strip() else None


def min_sdk():
    m = re.search(r"minSdk\s*=\s*(\d+)", (ROOT / "app" / "build.gradle.kts").read_text())
    return int(m.group(1)) if m else 1


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 16), b""):
            h.update(block)
    return h.hexdigest()


def release_key():
    """The release key settings and optional lineage: the PUREEQ_* variables when any key one is set,
    otherwise keystore.properties; None when neither (Gradle then signs with the debug key)."""
    if any(os.environ.get(env, "").strip() for name, env in SIGNING_NAMES.items() if name != "lineage"):
        return {name: os.environ.get(env, "") for name, env in SIGNING_NAMES.items()}
    if KEYSTORE_PROPS.exists():
        props = read_props(KEYSTORE_PROPS)
        return {name: props.get(name, "") for name in SIGNING_NAMES}
    if os.environ.get("PUREEQ_LINEAGE"):
        die("PUREEQ_LINEAGE is set but no release key is (docs/key-rotation.md).")
    return None


def apksigner():
    """apksigner from the newest build-tools of the Android SDK that Gradle uses."""
    sdk = read_props(ROOT / "local.properties").get("sdk.dir") or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    found = sorted(Path(sdk).glob("build-tools/*/apksigner"), key=lambda p: version_key(p.parent.name)) if sdk else []
    if not found:
        die("Can't find apksigner in the Android SDK's build-tools (set ANDROID_HOME, or sdk.dir in local.properties).")
    return str(found[-1])


def sign_with_lineage(built, apk, key):
    """Re-signs with the release key plus the lineage proving it took over from the old key, so phones
    with an APK signed by the old key accept the update (docs/key-rotation.md)."""
    def path(p):
        return str(Path(p) if Path(p).is_absolute() else ROOT / p)

    env = dict(os.environ, PUREEQ_KEYSTORE_PASSWORD=key["storePassword"], PUREEQ_KEY_PASSWORD=key["keyPassword"])
    subprocess.run([
        apksigner(), "sign",
        # Every phone PureEQ runs on (Android 9+) checks the v3 signature, which carries the lineage.
        # v1/v2 signatures would also need the oldest key of the lineage to sign.
        "--v1-signing-enabled", "false", "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
        "--v4-signing-enabled", "false", "--rotation-min-sdk-version", "28",
        "--lineage", path(key["lineage"]),
        "--ks", path(key["storeFile"]), "--ks-key-alias", key["keyAlias"],
        "--ks-pass", "env:PUREEQ_KEYSTORE_PASSWORD", "--key-pass", "env:PUREEQ_KEY_PASSWORD",
        "--out", str(apk), str(built),
    ], cwd=ROOT, check=True, env=env)


def signing_of(apk):
    """(SHA-256 digests of the APK's signer certificates, of its v3 lineage oldest first) by apksigner."""
    tool = apksigner()
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output([tool, "verify", "--print-certs", str(apk)]), re.M)
    lineage = subprocess.run([tool, "lineage", "--in", str(apk), "--print-certs"], cwd=ROOT, capture_output=True, text=True)
    # An APK without a v3 lineage makes apksigner fail here: it has no history then.
    history = re.findall(r"SHA-256 digest: ([0-9a-f]{64})", lineage.stdout) if lineage.returncode == 0 else []
    return certs, history


def accepts(installed, update):
    """Whether Android lets the update replace the installed APK: the app's Signers.accepts."""
    (mine, _), (theirs, history) = installed, update
    if not mine or not theirs:
        return False
    if set(mine) == set(theirs):
        return True
    return len(mine) == 1 and len(theirs) == 1 and mine[0] in history


def check_signing(apk):
    """Phones may still have the first published APK or already the latest: both must accept this one."""
    apks = sorted(DIST.glob("PureEQ-*.apk"), key=lambda p: version_key(p.stem))
    olds = list(dict.fromkeys(apks[:1] + apks[-1:]))
    new = signing_of(apk)
    for old in olds:
        if not accepts(signing_of(old), new):
            die(f"{apk.name} isn't signed with the key of dist/{old.name} or a lineage from it, so phones with that "
                "version would refuse it. Check keystore.properties or the PUREEQ_* variables (docs/key-rotation.md).")
    if olds:
        print(f"Signing key: accepted by phones with {' and '.join(p.name for p in olds)}.")


def confirm(question, assume_yes):
    if assume_yes:
        return True
    try:
        return input(question).strip().lower() in ("y", "yes")
    except EOFError:
        return False


def ensure_gh():
    if shutil.which("gh") is None:
        die("The GitHub CLI isn't installed. Run: brew install gh, then: gh auth login")
    if subprocess.run(["gh", "auth", "status"], capture_output=True).returncode != 0:
        die("The GitHub CLI isn't logged in. Run: gh auth login (use the account that should host PureEQ updates)")


def setup_repo(requested, assume_yes):
    configured = read_props(OTA_FILE).get("repo", "")
    repo = requested or configured
    if not repo:
        login = output(["gh", "api", "user", "--jq", ".login"])
        suggestion = f"{login}/pureeq-updates"
        print(f"GitHub CLI is logged in as {login}.")
        try:
            repo = input(f"Repository for PureEQ updates [{suggestion}]: ").strip() or suggestion
        except EOFError:
            repo = suggestion
    if not REPO_RE.match(repo):
        die(f"'{repo}' isn't a GitHub repository name (owner/name).")
    view = subprocess.run(["gh", "repo", "view", repo, "--json", "visibility", "--jq", ".visibility"], capture_output=True, text=True)
    if view.returncode != 0:
        print(
            f"\n{repo} doesn't exist yet. It will be a PUBLIC repository that holds only the APKs and update.json, "
            "no source code: phones download updates without logging in, so it has to be public."
        )
        if not confirm("Create it? [y/N] ", assume_yes):
            die("Nothing published.")
        run(["gh", "repo", "create", repo, "--public", "--add-readme", "--description", "PureEQ app updates (APK + update.json)"])
    elif view.stdout.strip() != "PUBLIC":
        die(f"{repo} is {view.stdout.strip().lower()}. Phones can only download updates from a public repository.")
    if repo != configured:
        write_props(OTA_FILE, {"repo": repo}, "GitHub repository that hosts PureEQ updates. Written by ota/publish.py.")
        if configured:
            print(f"Note: copies built for {configured} keep checking there. Install this build by hand once.")
    return repo


def main():
    ap = argparse.ArgumentParser(description="Build PureEQ and publish it as an over-the-air update.")
    ap.add_argument("--notes", help="release notes shown on the phone (default: this version's section of CHANGELOG.md)")
    ap.add_argument("--repo", help="owner/name of the update repository (first run, or to move it)")
    ap.add_argument("--dry-run", action="store_true", help="build and write ota/out/update.json without publishing")
    ap.add_argument("--skip-tests", action="store_true", help="skip the unit tests")
    ap.add_argument("--yes", action="store_true", help="don't ask before creating the repository")
    args = ap.parse_args()

    if args.dry_run:
        repo = args.repo or read_props(OTA_FILE).get("repo") or "OWNER/pureeq-updates"
        live = None
    else:
        ensure_gh()
        repo = setup_repo(args.repo, args.yes)
        try:
            live = published(repo)
        except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
            die(f"Couldn't read the current update notice at {manifest_url(repo)} ({e}).")

    version = read_props(VERSION_FILE)
    code, name = int(version["versionCode"]), version["versionName"]
    if live and code <= live[0]:
        code = live[0] + 1
        if version_key(name) <= version_key(live[1]):
            name = bump_patch(live[1])
        write_props(VERSION_FILE, {"versionCode": code, "versionName": name},
                    "Every published update needs a higher versionCode; ota/publish.py raises it when needed.")
        print(f"{live[1]} (build {live[0]}) is already published; this one is {name} (build {code}).")

    notes = args.notes or changelog_notes(name) or "Improvements and fixes."
    tasks = [":app:assembleGithubRelease"] if args.skip_tests else [":app:testGithubDebugUnitTest", ":app:assembleGithubRelease"]
    print(f"\nBuilding PureEQ {name} (build {code}) for {repo}…")
    # -Pota.repo bakes the update address into this build, so it can update itself next time.
    run(["./gradlew", "--console=plain", f"-Pota.repo={repo}", *tasks])

    # The APK waits in ota/out until its signature checks out; a dry run's stays there, so it can't
    # be mistaken for a published one in dist/.
    OUT.mkdir(parents=True, exist_ok=True)
    apk = OUT / f"PureEQ-{name}.apk"
    key = release_key()
    if key and key["lineage"]:
        sign_with_lineage(BUILT_APK, apk, key)
    else:
        shutil.copy2(BUILT_APK, apk)
    check_signing(apk)
    if not args.dry_run:
        DIST.mkdir(parents=True, exist_ok=True)
        apk = Path(shutil.move(str(apk), str(DIST / apk.name)))
    tag = f"v{name}"
    manifest = {
        "package": APP_ID,
        "versionCode": code,
        "versionName": name,
        "url": f"https://github.com/{repo}/releases/download/{tag}/{apk.name}",
        "sha256": sha256(apk),
        "size": apk.stat().st_size,
        "minSdk": min_sdk(),
        "notes": notes,
        "published": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    notice = OUT / "update.json"
    notice.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"\n{apk.relative_to(ROOT)}  {manifest['size']:,} bytes  sha256 {manifest['sha256'][:16]}…")
    print(f"{notice.relative_to(ROOT)} written.")

    if args.dry_run:
        print("Dry run: nothing published.")
        return

    run(["gh", "release", "create", tag, str(apk), str(notice), "--repo", repo, "--title", f"PureEQ {name}", "--notes", notes, "--latest"])
    for _ in range(12):
        try:
            seen = published(repo)
        except (urllib.error.URLError, OSError, ValueError, KeyError):
            seen = None
        if seen and seen[0] == code:
            print(f"\nLive: phones on an earlier PureEQ pick up {name} the next time the app opens, within 30 minutes on a charger with Wi-Fi, or within a day.")
            break
        time.sleep(5)
    else:
        print("\nPublished, but the update address still shows the previous version. GitHub can take a minute to catch up.")
    print(f"Manual install link (first install, or a phone without updates): https://github.com/{repo}/releases/latest")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as e:
        die(f"Stopped: {' '.join(map(str, e.cmd[:3]))}… failed (exit {e.returncode}).")
    except KeyboardInterrupt:
        die("Stopped.")
