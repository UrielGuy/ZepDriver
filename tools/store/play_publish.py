#!/usr/bin/env python3
"""Updates ZepDriver on Google Play through the Play Developer API.

Credentials: the service account JSON in the PLAY_SERVICE_ACCOUNT_JSON
environment variable (a CI secret). Every change goes into one Play "edit"
that is committed at the end, so a failure changes nothing.

  play_publish.py listing [--dry-run]
      Store listing (en-US) from store/assets/: title, short and full
      description, icon, feature graphic and phone screenshots (replacing
      the ones on Play).

  play_publish.py promote --to {alpha,beta,production} [--from internal]
                  [--rollout PERCENT] [--dry-run]
      Puts the newest release of one track on another: internal testing ->
      closed testing (alpha), open testing (beta) or production. Release
      notes come from store/assets/text/release-notes.txt. A rollout below
      100 starts a staged rollout.

Exit code 0 on success; errors from Play are printed as they come.
"""
import argparse
import glob
import json
import os
import re
import sys

PACKAGE = "com.zepdriver"
LANGUAGE = "en-US"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "store", "assets")


def read(rel):
    with open(os.path.join(ASSETS, rel), encoding="utf-8") as f:
        return f.read().strip()


def release_notes():
    text = read("text/release-notes.txt")
    m = re.search(r"<%s>\s*(.*?)\s*</%s>" % (LANGUAGE, LANGUAGE), text, re.S)
    return (m.group(1) if m else text).strip()


def service():
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        sys.exit("PLAY_SERVICE_ACCOUNT_JSON isn't set (store/play-setup.md, Part 5).")
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    creds = service_account.Credentials.from_service_account_info(
        json.loads(raw), scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False)


def listing(api, dry):
    title = read("text/title.txt")
    short = read("text/short-description.txt")
    full = read("text/full-description.txt")
    assert len(title) <= 30, "title over 30 characters"
    assert len(short) <= 80, "short description over 80 characters"
    assert len(full) <= 4000, "full description over 4000 characters"
    shots = sorted(glob.glob(os.path.join(ASSETS, "screenshots", "*.png")))
    assert 2 <= len(shots) <= 8, "Play needs 2 to 8 phone screenshots"
    images = [("icon", os.path.join(ASSETS, "graphics", "icon-512.png")),
              ("featureGraphic", os.path.join(ASSETS, "graphics", "feature-graphic.png"))]
    images += [("phoneScreenshots", s) for s in shots]
    print(f"listing: title {title!r}, short {len(short)} chars, full {len(full)} chars")
    for kind, path in images:
        print(f"  {kind}: {os.path.relpath(path, ROOT)}")
    if dry and api is None:
        return False
    edit = api.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
    if dry:
        # Proves the service account can open an edit, then throws it away.
        current = api.edits().listings().list(packageName=PACKAGE, editId=edit).execute()
        print(f"Play access OK; listings now on Play: {[l['language'] for l in current.get('listings', [])]}")
        api.edits().delete(packageName=PACKAGE, editId=edit).execute()
        return False
    from googleapiclient.http import MediaFileUpload
    api.edits().listings().update(
        packageName=PACKAGE, editId=edit, language=LANGUAGE,
        body={"language": LANGUAGE, "title": title, "shortDescription": short, "fullDescription": full},
    ).execute()
    for kind in {k for k, _ in images}:
        api.edits().images().deleteall(packageName=PACKAGE, editId=edit, language=LANGUAGE, imageType=kind).execute()
    for kind, path in images:
        api.edits().images().upload(
            packageName=PACKAGE, editId=edit, language=LANGUAGE, imageType=kind,
            media_body=MediaFileUpload(path, mimetype="image/png"),
        ).execute()
    api.edits().commit(packageName=PACKAGE, editId=edit).execute()
    print("listing updated")
    return True


def promote(api, source, target, rollout, dry):
    notes = release_notes()
    edit = api.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
    track = api.edits().tracks().get(packageName=PACKAGE, editId=edit, track=source).execute()
    releases = [r for r in track.get("releases", []) if r.get("versionCodes")]
    if not releases:
        sys.exit(f"nothing on the {source} track to promote")
    newest = max(releases, key=lambda r: max(int(v) for v in r["versionCodes"]))
    codes = newest["versionCodes"]
    release = {
        "name": newest.get("name") or f"Version {max(int(v) for v in codes)}",
        "versionCodes": codes,
        "releaseNotes": [{"language": LANGUAGE, "text": notes}],
    }
    if rollout < 100:
        release.update(status="inProgress", userFraction=rollout / 100.0)
    else:
        release["status"] = "completed"
    print(f"promote {source} -> {target}: versions {codes}, "
          f"{'staged ' + str(rollout) + '%' if rollout < 100 else 'full rollout'}")
    print(f"  notes: {notes}")
    if dry:
        api.edits().delete(packageName=PACKAGE, editId=edit).execute()
        return False
    api.edits().tracks().update(
        packageName=PACKAGE, editId=edit, track=target,
        body={"track": target, "releases": [release]},
    ).execute()
    api.edits().commit(packageName=PACKAGE, editId=edit).execute()
    print(f"{target} updated; Google reviews it before it goes live")
    return True


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("listing")
    l.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("promote")
    p.add_argument("--from", dest="source", default="internal")
    p.add_argument("--to", dest="target", required=True, choices=["alpha", "beta", "production"])
    p.add_argument("--rollout", type=int, default=100)
    p.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if a.cmd == "listing":
        # Checks the files even without credentials.
        if a.dry_run and not os.environ.get("PLAY_SERVICE_ACCOUNT_JSON"):
            listing(None, True)
            return
        listing(service(), a.dry_run)
    else:
        if not 1 <= a.rollout <= 100:
            sys.exit("--rollout is a percentage, 1 to 100")
        promote(service(), a.source, a.target, a.rollout, a.dry_run)


if __name__ == "__main__":
    main()
