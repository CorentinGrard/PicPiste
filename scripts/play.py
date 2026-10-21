#!/usr/bin/env python3
"""
Play Store tracks for PicPiste, through the Play Developer API (standard library + openssl only).

    play.py tracks                                   what each track holds
    play.py promote --from internal --to alpha       publish the latest internal release to the closed test
    play.py promote ... --dry-run                    show what would be promoted, change nothing

The service account key comes from the PLAY_SERVICE_ACCOUNT_JSON environment variable (its
contents) or from --key (a path to the .json file).
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = "fr.cvvm.picpiste"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/edits"


def b64(data: bytes) -> bytes:
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def access_token(key: dict) -> str:
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({
        "iss": key["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": key["token_uri"],
        "iat": now,
        "exp": now + 600,
    }).encode())
    message = header + b"." + claims
    fd, path = tempfile.mkstemp()
    try:
        with os.fdopen(fd, "w") as f:
            f.write(key["private_key"])
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", path], input=message, capture_output=True, check=True,
        ).stdout
    finally:
        os.unlink(path)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": (message + b"." + b64(signature)).decode(),
    }).encode()
    return json.load(urllib.request.urlopen(key["token_uri"], body))["access_token"]


class Edit:
    """A Play Console edit: changes are invisible until commit(), dropped by discard()."""

    def __init__(self, token: str):
        self.token = token
        self.committed = False
        self.id = self.call("POST", API)["id"]

    def call(self, method: str, url: str, payload: dict | None = None) -> dict:
        data = json.dumps(payload).encode() if payload is not None else None
        headers = {"Authorization": "Bearer " + self.token}
        if data is not None:
            headers["Content-Type"] = "application/json"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data, headers, method=method)) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            sys.exit(f"Play API {method} {url.split('/edits')[-1] or '/edits'}: HTTP {e.code}\n{e.read().decode()[:600]}")

    def tracks(self) -> list[dict]:
        return self.call("GET", f"{API}/{self.id}/tracks").get("tracks", [])

    def track(self, name: str) -> dict:
        return self.call("GET", f"{API}/{self.id}/tracks/{name}")

    def set_track(self, name: str, releases: list[dict]):
        self.call("PUT", f"{API}/{self.id}/tracks/{name}", {"track": name, "releases": releases})

    def commit(self):
        self.call("POST", f"{API}/{self.id}:commit")
        self.committed = True

    def discard(self):
        self.call("DELETE", f"{API}/{self.id}")


def describe(release: dict) -> str:
    return f"{release.get('name', '?')} [{release.get('status')}] versions {', '.join(release.get('versionCodes', []))}"


def cmd_tracks(edit: Edit, _args):
    for t in edit.tracks():
        releases = t.get("releases", [])
        print(f"{t['track']}: " + ("; ".join(describe(r) for r in releases) if releases else "empty"))


def cmd_promote(edit: Edit, args):
    source = [r for r in edit.track(args.source).get("releases", []) if r.get("status") == "completed"]
    if not source:
        sys.exit(f"Nothing published on {args.source}.")
    release = max(source, key=lambda r: max(int(v) for v in r["versionCodes"]))
    newest = max(int(v) for v in release["versionCodes"])
    for r in edit.track(args.target).get("releases", []):
        if r.get("status") == "completed" and max(int(v) for v in r.get("versionCodes", ["0"])) >= newest:
            sys.exit(f"{args.target} already has {describe(r)}: nothing newer on {args.source}.")
    promoted = {k: release[k] for k in ("name", "versionCodes", "releaseNotes") if k in release}
    promoted["status"] = "completed"
    print(f"{args.source} -> {args.target}: {describe(promoted)}")
    if args.dry_run:
        print("Dry run: nothing changed.")
        return
    edit.set_track(args.target, [promoted])
    edit.commit()
    print("Done. Closed and production tracks go through Google's review before testers get it.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", help="service account .json file (default: $PLAY_SERVICE_ACCOUNT_JSON)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("tracks", help="what each track holds")
    promote = sub.add_parser("promote", help="publish the latest release of one track to another")
    promote.add_argument("--from", dest="source", default="internal")
    promote.add_argument("--to", dest="target", default="alpha")
    promote.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    raw = open(args.key).read() if args.key else os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        sys.exit("No service account key: set PLAY_SERVICE_ACCOUNT_JSON or pass --key.")
    edit = Edit(access_token(json.loads(raw)))
    try:
        {"tracks": cmd_tracks, "promote": cmd_promote}[args.command](edit, args)
    finally:
        if not edit.committed:
            edit.discard()


if __name__ == "__main__":
    main()
