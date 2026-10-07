#!/usr/bin/env python3
"""The demo album: one CC0 photo per subject from Openverse, 36-40 subjects, no person, face, readable text, logo,
trademark or number plate in any of them (checked by eye, recorded per photo in album.json).

Two steps, so the choice made by eye is a file and the album can be rebuilt from it:

  K/venv/bin/python -I K/scripts/fetch_album.py search
      Queries Openverse once per subject (`license=cc0&mature=false&page_size=20`, first with
      `source=flickr,wikimedia,stocksnap`, then without a source when fewer than CANDIDATES photos pass), at least
      API_GAP_S apart (the anonymous limit is 20 calls a minute). A result passes when it is a photo (no sticker / png /
      clipart / illustration / vector / drawing ... in its title or tags, not a png / tiff / gif / svg file), is at
      least 800 px wide, and names the subject's noun in its title or tags; words for people or text in the title or
      tags drop it too (the eye check is still the rule). The first CANDIDATES passing results per subject are
      downloaded at 640 px into out/album_candidates/<slug>/ and drawn as contact sheets
      (out/album_candidates/sheet_<NN>.jpg, four subjects a sheet). Writes out/album_candidates/candidates.json.
  K/venv/bin/python -I K/scripts/fetch_album.py search_more <slug> "<query>" [extra,noun,words]
      Another query for one subject whose candidates all failed the eye check (sheet_more_<slug>.jpg).
  K/venv/bin/python -I K/scripts/fetch_album.py build
      Reads fixtures/album_picks.json (written after looking at the sheets and then at every chosen photo:
      {"picks": [{"slug", "openverse_id", "visual_check"}], "rejected": [{"id", "slug", "reason"}]}), downloads each
      chosen photo from its Openverse `url`, writes it as fixtures/album/a<NN>_<slug>.jpg (long side 1024 px, JPEG
      q=85, no EXIF, sRGB) and writes fixtures/album.json (id / subject / file / sha256 / width / height / source /
      foreign_landing_url / url / license / license_version / creator / title / visual_check, plus rejected).

The photos are not committed (.gitignore); album.json, album_picks.json and this script are the record.
"""
import concurrent.futures
import io
import json
import os
import re
import sys
import time

import requests
from PIL import Image, ImageDraw, ImageOps

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAND_DIR = os.path.join(K, "out", "album_candidates")
ALBUM_DIR = os.path.join(K, "fixtures", "album")
PICKS = os.path.join(K, "fixtures", "album_picks.json")
ALBUM_JSON = os.path.join(K, "fixtures", "album.json")
API = "https://api.openverse.org/v1/images/"
SOURCES = "flickr,wikimedia,stocksnap"
API_GAP_S = 3.2
CANDIDATES = 4
MIN_WIDTH = 800
LONG_SIDE = 1024
UA = {"User-Agent": "eg2demo-album-fetch/0.1 (on-device demo fixture; +https://github.com/john-rocky)"}

# (slug, subject as the launch names it, Openverse query, words of which one must be in the title or tags)
SUBJECTS = [
    ("red_bicycle", "red bicycle leaning on a wall", "red bicycle", ["bicycle", "bike"]),
    ("lighthouse", "lighthouse on the coast", "lighthouse coast", ["lighthouse"]),
    ("snow_mountain", "snow-capped mountain", "snow capped mountain", ["mountain", "mountains", "peak", "summit"]),
    ("sunflower_field", "sunflower field", "sunflower field", ["sunflower", "sunflowers"]),
    ("sailboat", "sailboat on the sea", "sailboat sea", ["sailboat", "sailing", "sail", "yacht"]),
    ("hot_air_balloon", "hot air balloon in the sky", "hot air balloon", ["balloon", "balloons"]),
    ("waterfall", "waterfall in a forest", "waterfall forest", ["waterfall", "falls", "cascade"]),
    ("coffee_cup", "cup of coffee on a table", "cup of coffee", ["coffee", "cup", "espresso", "cappuccino"]),
    ("typewriter", "old typewriter", "typewriter", ["typewriter"]),
    ("pizza", "pizza", "pizza", ["pizza"]),
    ("steam_locomotive", "steam locomotive", "steam locomotive", ["locomotive", "steam", "train"]),
    ("sand_dunes", "sand dunes in the desert", "sand dunes desert", ["dune", "dunes"]),
    ("sleeping_cat", "cat sleeping", "sleeping cat", ["cat", "cats", "kitten"]),
    ("dog_beach", "dog on a beach", "dog beach", ["dog", "dogs", "puppy"]),
    ("red_barn", "red barn", "red barn", ["barn"]),
    ("wooden_pier", "wooden pier on a lake", "wooden pier lake", ["pier", "jetty", "dock", "boardwalk"]),
    ("city_night", "city skyline at night", "city skyline night", ["skyline", "city", "night"]),
    ("forest_path", "path through a forest", "forest path", ["path", "trail", "forest", "woods"]),
    ("strawberries", "bowl of strawberries", "bowl strawberries", ["strawberry", "strawberries"]),
    ("violin", "violin", "violin", ["violin", "fiddle"]),
    ("bookshelf", "bookshelf full of books", "bookshelf books", ["bookshelf", "books", "bookcase", "library"]),
    ("stone_bridge", "stone bridge over a river", "stone bridge river", ["bridge"]),
    ("tulip_field", "tulip field", "tulip field", ["tulip", "tulips"]),
    ("rowing_boat", "rowing boat on a river", "rowing boat river", ["boat", "rowboat", "rowing"]),
    ("snowman", "snowman", "snowman", ["snowman"]),
    ("campfire", "campfire", "campfire", ["campfire", "fire", "bonfire"]),
    ("rainbow", "rainbow over fields", "rainbow field", ["rainbow"]),
    ("windmill", "windmill", "windmill", ["windmill", "mill"]),
    ("castle_hill", "castle on a hill", "castle hill", ["castle"]),
    ("cactus", "cactus", "cactus", ["cactus", "cacti", "saguaro"]),
    ("pumpkins", "pumpkins", "pumpkins", ["pumpkin", "pumpkins"]),
    ("canoe_lake", "canoe on a calm lake", "canoe lake", ["canoe", "kayak"]),
    ("paper_lantern", "paper lantern", "paper lantern", ["lantern", "lanterns"]),
    ("chess_board", "chess board", "chess board", ["chess", "chessboard"]),
    ("bananas", "bunch of bananas", "bananas", ["banana", "bananas"]),
    ("lemon_slices", "lemon slices", "lemon slices", ["lemon", "lemons"]),
    ("cherry_blossom", "cherry blossom tree", "cherry blossom", ["cherry", "blossom", "sakura"]),
    ("red_mailbox", "red mailbox", "red mailbox", ["mailbox", "postbox", "letterbox", "post box"]),
    ("yellow_taxi", "yellow taxi", "yellow taxi", ["taxi", "cab"]),
    ("umbrella_rain", "umbrella in the rain", "umbrella rain", ["umbrella"]),
    ("autumn_leaves", "autumn leaves", "autumn leaves", ["leaves", "leaf", "autumn", "fall"]),
    ("pine_cone", "pine cone", "pine cone", ["pine", "cone", "pinecone"]),
    ("bicycle_wheel", "bicycle wheel", "bicycle wheel", ["wheel"]),
    ("sliced_bread", "sliced bread", "sliced bread", ["bread", "loaf"]),
]

NOT_PHOTO = ["sticker", "png", "clipart", "clip art", "illustration", "vector", "drawing", "painting", "cartoon",
             "icon", "logo", "poster", "engraving", "lithograph", "watercolor", "watercolour", "sketch", "diagram",
             "render", "rendering", "3d", "pattern", "seamless", "mockup", "collage", "graphic", "design element",
             "isolated", "transparent", "cutout", "cut out", "scan"]
PEOPLE_OR_TEXT = ["person", "people", "man", "woman", "men", "women", "girl", "boy", "child", "children", "kid",
                  "kids", "portrait", "selfie", "family", "couple", "crowd", "tourist", "tourists", "face", "wedding",
                  "bride", "baby", "sign", "signage", "text", "quote", "menu", "newspaper", "advertisement",
                  "license plate", "number plate", "graffiti", "mural"]
BAD_TYPES = {"png", "tiff", "tif", "gif", "svg", "webp", "bmp"}


def words_of(r):
    title = (r.get("title") or "").lower()
    tags = " ".join((t.get("name") or "").lower() for t in (r.get("tags") or []))
    return title, tags


def has_word(text, w):
    return re.search(r"(?<![a-z])" + re.escape(w) + r"(?![a-z])", text) is not None


def passes(r, must_any):
    """(ok, reason) for one Openverse result."""
    if r.get("license") != "cc0":
        return False, "licence " + str(r.get("license"))
    if r.get("mature"):
        return False, "mature"
    ft = (r.get("filetype") or "").lower()
    url = (r.get("url") or "").lower()
    ext = url.rsplit(".", 1)[-1] if "." in url.rsplit("/", 1)[-1] else ""
    if ft in BAD_TYPES or ext in BAD_TYPES:
        return False, "file type " + (ft or ext)
    if (r.get("width") or 0) < MIN_WIDTH:
        return False, "width %s" % r.get("width")
    title, tags = words_of(r)
    both = title + " | " + tags
    for w in NOT_PHOTO:
        if has_word(both, w):
            return False, "not a photo: " + w
    for w in PEOPLE_OR_TEXT:
        if has_word(both, w):
            return False, "people or text word: " + w
    if not any(has_word(both, w) for w in must_any):
        return False, "subject noun not in title or tags"
    return True, ""


_last_call = [0.0]


def api(query, source):
    wait = _last_call[0] + API_GAP_S - time.time()
    if wait > 0:
        time.sleep(wait)
    params = {"q": query, "license": "cc0", "mature": "false", "page_size": 20}
    if source:
        params["source"] = source
    for attempt in range(3):
        _last_call[0] = time.time()
        resp = requests.get(API, params=params, headers=UA, timeout=30)
        if resp.status_code == 429:
            time.sleep(20)
            continue
        resp.raise_for_status()
        return resp.json(), resp.headers.get("x-ratelimit-available-anon_sustained")
    raise RuntimeError("Openverse kept answering 429 for " + query)


def fetch_bytes(url):
    resp = requests.get(url, headers=UA, timeout=60)
    resp.raise_for_status()
    return resp.content


def sized_url(c):
    """The 1280 px rendition (a width Commons serves) when the source is a large Wikimedia Commons original (its own thumbnail URL: the
    originals run to tens of MB), else the Openverse url (Flickr's is already 1024 px)."""
    url = c.get("url") or ""
    head = "https://upload.wikimedia.org/wikipedia/commons/"
    if url.startswith(head) and (c.get("width") or 0) > 1280 and "/thumb/" not in url:
        rest = url[len(head):]  # a/ab/File.jpg
        name = rest.rsplit("/", 1)[-1]
        return f"{head}thumb/{rest}/1280px-{name}"
    return url


def to_rgb(img):
    img = ImageOps.exif_transpose(img)
    if img.mode != "RGB":
        img = img.convert("RGB")
    return img


def search():
    os.makedirs(CAND_DIR, exist_ok=True)
    out = {"queried_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "api": API, "subjects": []}
    for slug, subject, query, must_any in SUBJECTS:
        seen, cands, dropped = set(), [], []
        for source in (SOURCES, None):
            if len(cands) >= CANDIDATES:
                break
            data, left = api(query, source)
            for r in data.get("results") or []:
                if r["id"] in seen:
                    continue
                seen.add(r["id"])
                ok, why = passes(r, must_any)
                if ok:
                    cands.append(r)
                else:
                    dropped.append({"id": r["id"], "title": r.get("title"), "reason": why})
            print(f"{slug}: query {query!r} source={source or 'any'} -> {data.get('result_count')} results, "
                  f"{len(cands)} pass (daily calls left {left})", flush=True)
        out["subjects"].append({"slug": slug, "subject": subject, "query": query, "candidates": cands[:CANDIDATES],
                                "more_passing": [c["id"] for c in cands[CANDIDATES:]], "dropped": dropped})
    json.dump(out, open(os.path.join(CAND_DIR, "candidates.json"), "w"), indent=1)

    jobs = [(s["slug"], k, c) for s in out["subjects"] for k, c in enumerate(s["candidates"])]

    def grab(job):
        slug, k, c = job
        d = os.path.join(CAND_DIR, slug)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, f"c{k}.jpg")
        try:
            img = to_rgb(Image.open(io.BytesIO(fetch_bytes(sized_url(c)))))
            img.thumbnail((640, 640))
            img.save(path, "JPEG", quality=80)
            return slug, k, path, None
        except Exception as e:  # noqa: BLE001 - a failed candidate is skipped and recorded
            return slug, k, None, repr(e)

    results = {}
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        for slug, k, path, err in ex.map(grab, jobs):
            results[(slug, k)] = (path, err)
            if err:
                print(f"{slug} c{k}: download failed {err}", flush=True)
    sheets(out["subjects"], results)


def sheets(subjects, results):
    tile_w, tile_h, label_h = 360, 270, 22
    per_sheet = 4
    for n in range(0, len(subjects), per_sheet):
        group = subjects[n:n + per_sheet]
        sheet = Image.new("RGB", (tile_w * CANDIDATES, (tile_h + label_h) * len(group)), (20, 20, 20))
        draw = ImageDraw.Draw(sheet)
        for row, s in enumerate(group):
            for k in range(CANDIDATES):
                x, y = k * tile_w, row * (tile_h + label_h)
                path, err = results.get((s["slug"], k), (None, "no candidate"))
                draw.text((x + 4, y + 4), f"{s['slug']} c{k}" + ("" if path else f" ({err or 'none'})"),
                          fill=(255, 255, 0))
                if path:
                    img = Image.open(path)
                    img.thumbnail((tile_w - 4, tile_h - 4))
                    sheet.paste(img, (x + 2, y + label_h))
        p = os.path.join(CAND_DIR, f"sheet_{n // per_sheet:02d}.jpg")
        sheet.save(p, "JPEG", quality=85)
        print("sheet", p, [s["slug"] for s in group], flush=True)


def search_more(slug, query, must_extra=""):
    """A second query for one subject whose first candidates all failed the eye check: up to 8 passing results,
    drawn as out/album_candidates/sheet_more_<slug>.jpg and kept in candidates.json under candidates_more."""
    path = os.path.join(CAND_DIR, "candidates.json")
    doc = json.load(open(path))
    entry = next(s for s in doc["subjects"] if s["slug"] == slug)
    must_any = next(m for sl, _, _, m in SUBJECTS if sl == slug) + [w for w in must_extra.split(",") if w]
    seen = {c["id"] for c in entry["candidates"]} | {c["id"] for c in entry.get("candidates_more", [])}
    found = []
    for source in (SOURCES, None):
        if len(found) >= 8:
            break
        data, left = api(query, source)
        for r in data.get("results") or []:
            if r["id"] in seen:
                continue
            seen.add(r["id"])
            ok, why = passes(r, must_any)
            if ok:
                found.append(r)
        print(f"{slug}: more query {query!r} source={source or 'any'} -> {data.get('result_count')} results, "
              f"{len(found)} pass", flush=True)
    found = found[:8]
    base = len(entry.get("candidates_more", []))
    entry.setdefault("candidates_more", []).extend(found)
    entry.setdefault("more_queries", []).append(query)
    json.dump(doc, open(path, "w"), indent=1)
    tile_w, tile_h, label_h = 360, 270, 22
    sheet = Image.new("RGB", (tile_w * 4, (tile_h + label_h) * 2), (20, 20, 20))
    draw = ImageDraw.Draw(sheet)
    def grab(c):
        try:
            img = to_rgb(Image.open(io.BytesIO(fetch_bytes(sized_url(c)))))
            img.thumbnail((640, 640))
            return img, None
        except Exception as e:  # noqa: BLE001
            return None, repr(e)

    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        got = list(ex.map(grab, found))
    d = os.path.join(CAND_DIR, slug)
    os.makedirs(d, exist_ok=True)
    for k, (img, err) in enumerate(got):
        x, y = (k % 4) * tile_w, (k // 4) * (tile_h + label_h)
        label = f"{slug} m{base + k}"
        if img is not None:
            img.save(os.path.join(d, f"m{base + k}.jpg"), "JPEG", quality=80)
            img.thumbnail((tile_w - 4, tile_h - 4))
            sheet.paste(img, (x + 2, y + label_h))
        else:
            label += f" ({err})"[:60]
        draw.text((x + 4, y + 4), label, fill=(255, 255, 0))
    out = os.path.join(CAND_DIR, f"sheet_more_{slug}.jpg")
    sheet.save(out, "JPEG", quality=85)
    print("sheet", out, flush=True)


def build():
    picks = json.load(open(PICKS))
    cands = json.load(open(os.path.join(CAND_DIR, "candidates.json")))
    by_id = {}
    for s in cands["subjects"]:
        for c in s["candidates"] + s.get("candidates_more", []):
            by_id[c["id"]] = (s, c)
    os.makedirs(ALBUM_DIR, exist_ok=True)
    for f in os.listdir(ALBUM_DIR):
        if f.endswith(".jpg"):
            os.remove(os.path.join(ALBUM_DIR, f))
    rows = []
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        raw = list(ex.map(lambda p: fetch_bytes(sized_url(by_id[p["openverse_id"]][1])), picks["picks"]))
    for n, p in enumerate(picks["picks"], start=1):
        s, c = by_id[p["openverse_id"]]
        assert s["slug"] == p["slug"], (s["slug"], p["slug"])
        img = to_rgb(Image.open(io.BytesIO(raw[n - 1])))
        img.thumbnail((LONG_SIDE, LONG_SIDE), Image.LANCZOS)
        aid = f"a{n:02d}"
        name = f"{aid}_{p['slug']}.jpg"
        path = os.path.join(ALBUM_DIR, name)
        img.save(path, "JPEG", quality=85)  # a new file from pixels only: no EXIF, no ICC profile carried over
        data = open(path, "rb").read()
        import hashlib
        rows.append({"id": aid, "subject": s["subject"], "slug": p["slug"], "file": name,
                     "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                     "width": img.size[0], "height": img.size[1], "source": c.get("source"),
                     "provider": c.get("provider"), "openverse_id": c["id"],
                     "foreign_landing_url": c.get("foreign_landing_url"), "url": c.get("url"),
                     "downloaded_from": sized_url(c),
                     "license": c.get("license"), "license_version": c.get("license_version"),
                     "creator": c.get("creator"), "title": c.get("title"), "visual_check": p["visual_check"]})
        print(f"{aid} {p['slug']}: {img.size[0]}x{img.size[1]} {len(data)} B  {c.get('source')}  {c.get('title')!r}",
              flush=True)
    doc = {"built_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "count": len(rows),
           "rule": "CC0 photos from Openverse; long side 1024 px, JPEG q=85, no EXIF; no person, face, readable text, "
                   "logo, trademark or number plate (checked by eye, visual_check per photo)",
           "album": rows, "rejected": picks.get("rejected", [])}
    json.dump(doc, open(ALBUM_JSON, "w"), indent=1, ensure_ascii=False)
    print(f"{len(rows)} photos -> {ALBUM_JSON}")


if __name__ == "__main__":
    if sys.argv[1] == "search_more":
        search_more(*sys.argv[2:])
    else:
        {"search": search, "build": build}[sys.argv[1]]()
