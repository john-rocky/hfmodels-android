#!/usr/bin/env python3
"""Puts the three example photos of samples/look into src/main/assets/examples/.

The repository holds no images (.gitignore), so the photos are kept by reference: each entry of examples.json names the
URL its bytes come from, their size and their sha256. This script downloads each photo, checks both, and writes it
under the file name the app reads (Examples.kt). A file already in place with the right sha256 is left alone.

  python3 samples/look/tools/fetch_examples.py           # fetch what is missing, then check all three
  python3 samples/look/tools/fetch_examples.py --check   # only check the files already in assets (no network)

Standard library only. Exit status 0 when all three are in place and match, 1 otherwise, with the reason per photo.
The photos are CC0 1.0 (examples.json, the README). When a URL stops serving these bytes, the script says so and
names the photo's Openverse record and source page, where the photo can be found again.
"""
import argparse
import hashlib
import json
import os
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "examples.json")
ASSETS = os.path.join(os.path.dirname(HERE), "src", "main", "assets", "examples")
USER_AGENT = "hfmodels-look-examples/1 (+https://github.com/john-rocky/hfmodels-android)"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def write_atomically(path, data):
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".fetch-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="only check the files already in assets")
    args = parser.parse_args()
    examples = json.load(open(MANIFEST))["examples"]
    os.makedirs(ASSETS, exist_ok=True)
    failed = 0
    for e in examples:
        path = os.path.join(ASSETS, e["file"])
        where = os.path.relpath(path)
        if os.path.exists(path):
            have = open(path, "rb").read()
            if len(have) == e["bytes"] and sha256(have) == e["sha256"]:
                print(f"ok      {e['id']}: {where} ({e['bytes']:,} bytes, sha256 {e['sha256'][:12]}…) already in place")
                continue
            if args.check:
                print(f"DIFFERS {e['id']}: {where} has {len(have):,} bytes, sha256 {sha256(have)[:12]}…; "
                      f"expected {e['bytes']:,} bytes, sha256 {e['sha256'][:12]}… (run without --check to fetch it again)")
                failed += 1
                continue
        elif args.check:
            print(f"MISSING {e['id']}: {where} (run without --check to fetch it)")
            failed += 1
            continue
        try:
            data = download(e["url"])
        except Exception as x:  # noqa: BLE001 - any failure is reported per photo
            print(f"FAILED  {e['id']}: could not download {e['url']}: {x}")
            failed += 1
            continue
        if len(data) != e["bytes"] or sha256(data) != e["sha256"]:
            print(f"FAILED  {e['id']}: {e['url']} sent {len(data):,} bytes, sha256 {sha256(data)[:12]}…; expected "
                  f"{e['bytes']:,} bytes, sha256 {e['sha256'][:12]}…. The source no longer serves the same file: find "
                  f"the photo again from {e['openverse_record']} or {e['source_page']}. Nothing was written.")
            failed += 1
            continue
        write_atomically(path, data)
        print(f"fetched {e['id']}: {where} ({len(data):,} bytes, sha256 {e['sha256'][:12]}…, {e['license']}) from {e['url']}")
    if failed:
        print(f"{failed} of {len(examples)} example photos are not in place.")
        return 1
    print(f"all {len(examples)} example photos are in place.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
