#!/usr/bin/env python3
"""Department website registry: roadmap, graduation requirement, professor and announcement page URLs for every major.

The list of majors comes from the app repo (shared/data/departments.json, 62 departments).
URLs are saved to sites/department-sites.json, one entry per major. Nothing is fetched from the web.

    python3 tools/department-sites.py              # show every major
    python3 tools/department-sites.py list --todo  # only majors that still have empty fields
    python3 tools/department-sites.py edit         # fill majors with no URLs yet, one by one
    python3 tools/department-sites.py edit 경영학과  # edit one major (part of the name is enough)
    python3 tools/department-sites.py serve        # edit in the browser at http://127.0.0.1:8765

While editing: Enter keeps the current value, "-" clears it, "q" saves and quits.
The file is saved after every major, so you can stop at any time.
"""

import json
import sys
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPARTMENTS = ROOT.parent / "cuk-moa-kmp" / "shared" / "data" / "departments.json"
import os

# DEPARTMENT_SITES points at another file (for trying things out without touching the real list)
REGISTRY = Path(os.environ.get("DEPARTMENT_SITES", ROOT / "sites" / "department-sites.json")).resolve()
PAGE = Path(__file__).resolve().parent / "department-sites.html"
PORT = 8765

# Sites the server already scrapes (Scraper.kt DEPT_BOARDS / FACULTY_PAGES), so they start filled in.
KNOWN = {
    "컴퓨터정보공학부": ("csie", "faculty", ["notice", "career", "news"]),
    "미디어기술콘텐츠학과": ("mtc", "faculty", ["notice", "news", "notice1"]),
    "데이터사이언스학과": ("datascience", "faculty", ["notice", "recruitment"]),
    "정보통신전자공학부": ("ice", "teacher", ["notice", "notice01", "ice-news"]),
    "인공지능학과": ("ai", "faculty", ["notice", "contest", "graduatenotice"]),
}


class Quit(Exception):
    pass


# Single-URL fields and fields that can hold several pages (some departments split professors over pages)
SINGLE = ("site", "roadmap", "graduation")
MULTI = ("professors", "announcements")
LABELS = {"site": "site", "roadmap": "roadmap", "graduation": "graduation",
          "professors": "professors", "announcements": "announcement"}


def known_entry(name):
    code, faculty, boards = KNOWN[name]
    base = f"https://{code}.catholic.ac.kr/{code}"
    return {
        "site": f"https://{code}.catholic.ac.kr",
        "roadmap": f"{base}/course/roadmap.do",
        "professors": [f"{base}/faculty/{faculty}.do"],
        "announcements": [f"{base}/community/{b}.do" for b in boards],
    }


def blank(d):
    return {"department": d["name"], "campus": d["campus"], "college": d["college"],
            **{k: "" for k in SINGLE}, **{k: [] for k in MULTI}}


def normalize(e):
    """Fills missing fields; an older file stored professors as one URL."""
    for k in SINGLE:
        e[k] = e.get(k) or ""
    for k in MULTI:
        v = e.get(k) or []
        e[k] = [v] if isinstance(v, str) else list(v)
    return e


def load():
    departments = json.loads(DEPARTMENTS.read_text(encoding="utf-8"))["items"]
    saved = {}
    if REGISTRY.exists():
        saved = {e["department"]: normalize(e) for e in json.loads(REGISTRY.read_text(encoding="utf-8"))}
    entries = []
    for d in departments:
        e = blank(d)
        if d["name"] in KNOWN:
            e.update(known_entry(d["name"]))
        if d["name"] in saved:
            old = saved[d["name"]]
            e.update({k: old[k] for k in SINGLE + MULTI})
        entries.append(e)
    # Keep majors added by hand that aren't in departments.json
    names = {e["department"] for e in entries}
    entries += [e for name, e in saved.items() if name not in names]
    return entries


def save(entries):
    REGISTRY.parent.mkdir(parents=True, exist_ok=True)
    REGISTRY.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def missing(e):
    return [k for k in SINGLE + MULTI if not e[k]]


def show(e):
    print(f"{e['department']} ({e['campus']} · {e['college']}):")
    for k in ("site", "roadmap", "graduation", "professors", "announcements"):
        if k in SINGLE:
            print(f"  {LABELS[k]}: {e[k]}")
        elif not e[k]:
            print(f"  {LABELS[k]}: ")
        else:
            for i, url in enumerate(e[k], 1):
                print(f"  {LABELS[k]}{i}: {url}")


def ask(label, current):
    while True:
        value = input(f"  {label} [{current}]: ").strip()
        if value == "q":
            raise Quit
        if value == "":
            return current
        if value == "-":
            return ""
        if value.startswith(("http://", "https://")):
            return value
        print("    URL은 http:// 또는 https://로 시작해야 해요 (지우려면 -, 그만하려면 q)")


def ask_list(label, current):
    urls = []
    for url in current:
        value = ask(f"{label}{len(urls) + 1}", url)
        if value:
            urls.append(value)
    # Add more pages until an empty answer
    while True:
        value = ask(f"{label}{len(urls) + 1} (추가, 없으면 Enter)", "")
        if not value:
            return urls
        urls.append(value)


def edit(e):
    print()
    show(e)
    print("  (Enter = 그대로, - = 지우기, q = 저장하고 끝내기)")
    for k in ("site", "roadmap", "graduation"):
        e[k] = ask(LABELS[k], e[k])
    for k in MULTI:
        e[k] = ask_list(LABELS[k], e[k])


def serve():
    """Local editing page. Listens on 127.0.0.1 only and only writes the registry file."""
    allowed_hosts = {f"127.0.0.1:{PORT}", f"localhost:{PORT}"}

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body, content_type="text/plain; charset=utf-8"):
            data = body.encode("utf-8") if isinstance(body, str) else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def allowed(self):
            # Blocks other websites (DNS rebinding) from reaching this page through the browser
            if self.headers.get("Host") not in allowed_hosts:
                self.reply(403, "forbidden")
                return False
            return True

        def do_GET(self):
            if not self.allowed():
                return
            if self.path == "/":
                self.reply(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            elif self.path == "/api/sites":
                self.reply(200, json.dumps(load(), ensure_ascii=False), "application/json; charset=utf-8")
            else:
                self.reply(404, "not found")

        def do_POST(self):
            if not self.allowed():
                return
            # A JSON content type means another site's page can't send this without a CORS preflight, which is never answered
            if self.path != "/api/sites" or not self.headers.get("Content-Type", "").startswith("application/json"):
                self.reply(400, "bad request")
                return
            try:
                e = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                if not all(isinstance(e[k], str) for k in SINGLE) or not all(isinstance(e[k], list) for k in MULTI):
                    raise ValueError("형식이 맞지 않아요")
                urls = [e[k] for k in SINGLE] + [u for k in MULTI for u in e[k]]
                if not all(isinstance(u, str) and (u == "" or u.startswith(("http://", "https://"))) for u in urls):
                    raise ValueError("URL은 http:// 또는 https://로 시작해야 해요")
            except (ValueError, KeyError, TypeError) as err:
                self.reply(400, str(err))
                return
            entries = load()
            target = next((x for x in entries if x["department"] == e.get("department")), None)
            if target is None:
                self.reply(404, "학과가 없어요")
                return
            target.update({k: e[k] for k in SINGLE})
            target.update({k: [u for u in e[k] if u] for k in MULTI})
            save(entries)
            self.reply(200, "ok")

        def log_message(self, *args):
            pass

    url = f"http://127.0.0.1:{PORT}"
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"{url} 에서 편집하세요 · 저장 위치 {REGISTRY} · 끝내려면 Ctrl+C")
    if "--no-open" not in sys.argv:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()


def main(args):
    entries = load()
    command = args[0] if args else "list"
    if command == "list":
        todo = "--todo" in args
        shown = [e for e in entries if not todo or missing(e)]
        for e in shown:
            show(e)
            print()
        done = sum(1 for e in entries if not missing(e))
        print(f"{done}/{len(entries)}개 학과 완료 · {REGISTRY}")
        save(entries)
    elif command == "edit":
        query = " ".join(a for a in args[1:])
        if query:
            q = query.replace(" ", "")
            targets = [e for e in entries if e["department"] == query] or \
                [e for e in entries if q in e["department"].replace(" ", "")]
            if not targets:
                sys.exit(f"'{query}' 학과가 없어요")
        else:
            targets = [e for e in entries if len(missing(e)) == len(SINGLE + MULTI)]
        try:
            for e in targets:
                edit(e)
                save(entries)
                print(f"  저장했어요 ({REGISTRY})")
        except (Quit, KeyboardInterrupt, EOFError):
            print()
        save(entries)
    elif command == "serve":
        save(load())
        serve()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
