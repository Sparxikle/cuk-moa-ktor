# 가대모아 (CUK Moa) — Server

API server and school information collector for the [가대모아 app](https://github.com/Sparxikle/cuk-moa-kmp). Kotlin + Ktor 3, SQLite.

- **Automatically collects the last 12 months of announcements** from school notices, LMS, events, external contests and every department board listed in the site registry (`sites/department-sites.json`, edited with `tools/department-sites.py`): 106 boards for 48 majors, about 6,400 posts as of 2026-11-18.
  New posts only every 30 minutes, and a full collection once a day (refreshes view counts and pinned status). A restart runs the full collection only if the last one is over 24 h old.
- Provides the school's **academic calendar** (per academic year) and the **full list of school departments** (62) from the Public Data Portal (data.go.kr), refreshed daily.
- Collects **professor profiles** (423) and **official major roadmaps** (54) from the professor and roadmap pages in the same registry, once a day. Pages in a layout the parsers don't read are skipped and logged. Roadmaps published only as images are hand-transcribed in `src/main/resources/roadmaps/`.
- Serves **this semester's open classes** (1,343 for 2026-2), imported once a semester, and each department's **related jobs**.
- Builds **AI timetables** with Amazon Bedrock and checks every one (clashes, credits, free days) before answering.
- Provides the **catalog**: courses, the real graduation requirements of 45 departments (students admitted in 2026), and sample career tracks and senior records. It is the app repo's `shared/data/sample-catalog.json`; because the senior records are still sample data, `/health` reports `"dataMode": "sample"` and the app labels those records "(예시)".

## The app repo is required

The API models (`shared`) and the catalog live in the app repo. Clone both repos side by side.

```sh
git clone https://github.com/Sparxikle/cuk-moa-kmp.git
git clone https://github.com/Sparxikle/cuk-moa-ktor.git
cd cuk-moa-ktor && ./gradlew test
```

The server looks for `cuk-moa-kmp` in the sibling folder. If it is elsewhere, add `-PappRepo=/path/to/app`. The app and server compile against the same model code, so an API mismatch fails the server build.

## Running

```sh
./gradlew run                      # API on :8080 + announcement collection
./gradlew run --args="--once"      # One full collection (boards, calendar, professors, roadmaps), then exit
./gradlew run --args="--once --incremental"   # Scrape new posts only, then exit
./gradlew run --args="--fetch"     # Serve, and start with a full scrape even if the last one is under 24 h old
```

| Environment variable | Default | |
| --- | --- | --- |
| `PORT` | `8080` | |
| `DATABASE_PATH` | `data/announcements.db` | Created if missing |
| `HOST` | `0.0.0.0` | Bind address. `127.0.0.1` behind a reverse proxy on the same machine |
| `SCRAPE_INTERVAL_MIN` | `30` | Interval for collecting new posts |
| `SCRAPE_ENABLED` | `true` | `false` = API only |
| `SITES_PATH` | `sites/department-sites.json` | Department site registry: boards, professor pages, roadmaps, graduation links. Restart after editing |
| `CATALOG_PATH` | none | Catalog JSON to use instead of the bundled `sample-catalog.json`. The server won't start if references are broken |
| `BEDROCK_MODEL_ID` | none | Amazon Bedrock model ID or inference profile ID for the AI timetable maker. Without it, `POST /api/v1/timetables` answers 503. Credentials come from the AWS default provider chain only: the EC2 instance role in production (needs `bedrock:InvokeModel`), `~/.aws` or `AWS_*` variables locally. No key is stored on the server |
| `CAREERNET_API_KEY` | none | CareerNet (career.go.kr) Open API key for job descriptions in `GET /api/v1/jobs`. Without it, jobs come back as names only |
| `AWS_REGION` | `ap-northeast-2` | Bedrock region |
| `DATA_GO_API_KEY` | none | Public Data Portal "University Department Information" API key. Without it, the department list comes from the snapshot in the app repo |

Settings are read from environment variables first, then from `.env` in the server folder. Copy `.env.example` to `.env`. **`.env` is not tracked by git** (never commit the key).

Importing the semester's open classes (the school's 개설과목 export needs a login, so download `classes.json` by hand once a semester). It replaces that semester's classes in the database; restart the server afterwards:

```sh
./gradlew run --args="--import-classes ../classes.json"
```

Refreshing the department snapshot (saved in the app repo; commit it to update the app bundle):

```sh
./gradlew run --args="--export-departments ../cuk-moa-kmp/shared/data/departments.json"
```

## API

Full spec with request and response schemas: **Swagger UI at [`/swagger`](https://54-117-83-53.sslip.io/swagger)** (raw spec at `/swagger/documentation.yaml`, source in `src/main/resources/openapi/documentation.yaml`). `OpenApiTest` fails when a route and the spec disagree.

| Path | |
| --- | --- |
| `GET /health` | Server status, data mode, last scrape time |
| `GET /api/v1/catalog` | Catalog |
| `GET /api/v1/courses/{id}` | Single course |
| `GET /api/v1/announcements` | Announcement list (`source`, `major`, `category`, `board`, `q`, `since`, `pinnedFirst`, `limit`, `offset`) |
| `GET /api/v1/announcements/{id}` | Single announcement |
| `GET /api/v1/announcements/meta` | Sources, majors, boards with post counts, and `scrapeRuns`: the last success and error of every collected page (179 as of 2026-11-18: 117 department boards, 55 roadmap pages, 4 school boards, 2 calendar years, professors) |
| `GET /api/v1/departments` | All school departments (by college, excluding discontinued and admission-only units) |
| `GET /api/v1/calendar?campus=성심` | School academic calendar (성심 · 성의 · 성신 campuses) |
| `GET /api/v1/jobs?department=성심/경영학과` | A department's related jobs (names from the 대교협 department data). With `CAREERNET_API_KEY`, also CareerNet descriptions, salary and link: the whole CareerNet encyclopedia is downloaded every 30 days and names are matched on the server |
| `POST /api/v1/timetables` | AI timetable suggestions: 3 checked timetables (no clashes, credit range, free days). Limited to 12 AI calls/min, 450/day, 10 timetables/hour per IP to keep Bedrock costs down |
| `GET /api/v1/classes` | Open classes of the latest imported semester, one row per class number (`department`, `category`, `grade`, `q` = class or professor name, `year`, `semester`) |
| `GET /api/v1/professors` | Professor profiles from department websites (`major`, e.g. `?major=ai,csie`; department chairs first, then by name) |
| `GET /api/v1/roadmaps` | Official department major roadmaps (`major`) |

For announcements, `major` applies only to department posts (`source=dept`). Example: `?source=dept,main&major=ai,csie` = both majors' boards + school notices.

Errors: `{"error": {"code": "COURSE_NOT_FOUND", "message": "과목을 찾을 수 없습니다."}}` (messages are in Korean and shown to users as is)

CORS is open for GET and POST, so Swagger UI's "Try it out" works in the browser. The Bedrock cost is capped by the per-IP limits above, not by CORS.

## Layout

```
src/main/kotlin/.../server/
  Application.kt      Config, routes (module()), CORS, scrape schedule, command-line tools
  db/Database.kt      SQLite (WAL, FTS5 trigram search, numbered migrations)
  scrape/Scraper.kt   Board list from the registry, pagination, dedupe (by post number), new-posts-only mode,
                      professors, roadmaps, calendar; records every run in scrape_runs
  scrape/Parsers.kt   HTML → announcements, professors, roadmaps, calendar events
  SiteRegistry.kt     Reads sites/department-sites.json
  Departments.kt      Public Data Portal department list (daily, snapshot fallback)
  Classes.kt          개설과목 import and /api/v1/classes
  Jobs.kt             Related jobs, CareerNet encyclopedia (every 30 days, with a key)
  Timetables.kt       AI timetables on Bedrock, checks, per-IP limits, 10-minute cache
src/main/resources/
  db/0001–0007_*.sql  Schema migrations (announcements, calendar, faculty, roadmaps, classes, jobs, CareerNet)
  openapi/            OpenAPI spec served at /swagger
  roadmaps/           Hand-transcribed roadmaps for image-only pages
sites/                department-sites.json: boards, professor pages, roadmaps and graduation links per major
deploy/               Production systemd unit and Caddyfile
src/test/             Parser tests on saved real pages (resources/fixtures), API, OpenAPI, timetables, jobs, registry
tools/department-sites.py   Registry editor (terminal, or `serve` for a browser page)
tools/python-scraper/       The original Python scraper (for reference)
```

Adding a department site or board: one entry in `sites/department-sites.json` (`python3 tools/department-sites.py serve`), then restart.

## Tests

```sh
./gradlew test
```

51 tests: parsers on saved real pages (14), API responses with the shared models and CORS (10), AI timetables (9), data sources (6), jobs (5), site registry (5), routes ↔ OpenAPI spec (2).

## Deployment (AWS EC2, systemd + Caddy)

Production runs on one EC2 instance: **t4g.medium** (ARM, 4 GB) with **Amazon Linux 2023**. The server is a systemd service and **Caddy** in front of it handles HTTPS (Let's Encrypt, renewed automatically). SQLite lives on the instance's disk, in `/opt/cuk-moa/data`. The files used in production are in [`deploy/`](deploy/).

**Set up a new server (once)**

```sh
sudo dnf install -y java-21-amazon-corretto-headless
# Caddy: see https://caddyserver.com/docs/install
sudo useradd --system --home-dir /opt/cuk-moa --shell /sbin/nologin cukmoa
sudo mkdir -p /opt/cuk-moa/data /opt/cuk-moa/sites
# Copy server.jar, sites/department-sites.json and .env (from .env.example) into /opt/cuk-moa
sudo chown -R cukmoa:cukmoa /opt/cuk-moa && sudo chmod 600 /opt/cuk-moa/.env
sudo cp deploy/cuk-moa.service /etc/systemd/system/
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile   # change the site name to this server's domain or sslip.io name
sudo systemctl daemon-reload && sudo systemctl enable --now cuk-moa caddy
```

- The service runs as `cukmoa`, listens on `127.0.0.1:8080` only (Caddy is the only way in), and can write only to `/opt/cuk-moa/data`. It restarts on failure.
- **Bedrock:** attach an IAM instance role that allows `bedrock:InvokeModel` (production: `cukmoa-server-role`) and set `BEDROCK_MODEL_ID` in `.env`. No AWS key goes on the server.
- Open ports 80 and 443 in the security group (Caddy needs 80 for the certificate check).

**Update the server**

```sh
./gradlew fatJar                                   # build/libs/server-all.jar
scp build/libs/server-all.jar <server>:/tmp/server-new.jar
ssh <server>
sudo cp -p /opt/cuk-moa/server.jar /opt/cuk-moa/server.jar.bak-$(date +%Y%m%d)
sudo install -o cukmoa -g cukmoa -m 755 /tmp/server-new.jar /opt/cuk-moa/server.jar
sudo systemctl restart cuk-moa
curl -s http://127.0.0.1:8080/health
```

To roll back, copy the `.bak` jar back and restart. If `sites/department-sites.json` changed, copy it to `/opt/cuk-moa/sites/` too.

**Import the semester's open classes on the server**

```sh
cd /opt/cuk-moa && sudo -u cukmoa java -jar server.jar --import-classes /tmp/classes.json
sudo systemctl restart cuk-moa
```

Logs: `journalctl -u cuk-moa -f`.
