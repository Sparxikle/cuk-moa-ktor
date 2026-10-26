# 가대모아 (CUK Moa) — Server
## License

API server and school information collector for the [가대모아 app](https://github.com/Sparxikle/cuk-moa-kmp). Kotlin + Ktor 3, SQLite.

- **Automatically collects this year's announcements** from school notices, LMS, events, external contests and 14 boards of the 5 departments in the College of AI, Electronics & Information Convergence (18 sources in total).
  Full scrape on startup, new posts only every 30 minutes, and a full rescrape once a day (refreshes view counts and pinned status).
- Provides the school's **academic calendar** (per academic year) and the **full list of school departments** from the Public Data Portal (data.go.kr).
- Collects **professor profiles** and **official major roadmaps** from the websites of the same 5 departments.
- Provides the **catalog** of courses, career tracks, roadmaps, professors and graduation requirements (currently the sample catalog from the app repo).

## The app repo is required

The API models (`shared`) and the sample catalog live in the app repo. Clone both repos side by side.

```sh
git clone https://github.com/Sparxikle/cuk-moa-kmp.git
git clone https://github.com/Sparxikle/cuk-moa-ktor.git
cd cuk-moa-ktor && ./gradlew test
```

The server looks for `cuk-moa-kmp` in the sibling folder. If it is elsewhere, add `-PappRepo=/path/to/app`. The app and server compile against the same model code, so an API mismatch fails the server build.

## Running

```sh
./gradlew run                      # API on :8080 + announcement collection
./gradlew run --args="--once"      # One full scrape, then exit (about 1 min 30 s; 1,997 posts as of 2026-10-25)
./gradlew run --args="--once --incremental"   # Scrape new posts only, then exit
```

| Environment variable | Default | |
| --- | --- | --- |
| `PORT` | `8080` | |
| `DATABASE_PATH` | `data/announcements.db` | Created if missing |
| `SCRAPE_INTERVAL_MIN` | `30` | Interval for collecting new posts |
| `SCRAPE_ENABLED` | `true` | `false` = API only |
| `CATALOG_PATH` | none | Real catalog JSON. Sample if unset. The server won't start if references are broken |
| `DATA_GO_API_KEY` | none | Public Data Portal "University Department Information" API key. Without it, the department list comes from the snapshot in the app repo |

Settings are read from environment variables first, then from `.env` in the server folder. Copy `.env.example` to `.env`. **`.env` is not tracked by git** (never commit the key).

Refreshing the department snapshot (saved in the app repo; commit it to update the app bundle):

```sh
./gradlew run --args="--export-departments ../cuk-moa-kmp/shared/data/departments.json"
```

## API

| Path | |
| --- | --- |
| `GET /health` | Server status, data mode, last scrape time |
| `GET /api/v1/catalog` | Catalog |
| `GET /api/v1/courses/{id}` | Single course |
| `GET /api/v1/announcements` | Announcement list (`source`, `major`, `category`, `board`, `q`, `since`, `pinnedFirst`, `limit`, `offset`) |
| `GET /api/v1/announcements/{id}` | Single announcement |
| `GET /api/v1/announcements/meta` | Department names, announcement count per board, scrape status per board |
| `GET /api/v1/departments` | All school departments (by college, excluding discontinued and admission-only units) |
| `GET /api/v1/calendar?campus=성심` | School academic calendar (성심 · 성의 · 성신 campuses) |
| `GET /api/v1/professors` | Professor profiles from department websites (`major`, e.g. `?major=ai,csie`; department chairs first, then by name) |
| `GET /api/v1/roadmaps` | Official department major roadmaps (`major`) |

For announcements, `major` applies only to department posts (`source=dept`). Example: `?source=dept,main&major=ai,csie` = both majors' boards + school notices.

Errors: `{"error": {"code": "COURSE_NOT_FOUND", "message": "과목을 찾을 수 없습니다."}}` (messages are in Korean and shown to users as is)

## Layout

```
src/main/kotlin/.../server/
  Application.kt      Routes, config, scrape schedule
  db/Database.kt      SQLite (WAL, FTS5 trigram search, migrations)
  scrape/Parsers.kt   Board HTML → announcements (same selectors as tools/python-scraper/test.py)
  scrape/Scraper.kt   Board list, pagination, dedupe (by post number), new-posts-only mode
src/main/resources/db/0001_init.sql
src/test/             API tests + scraper tests against real board HTML (fixtures)
tools/python-scraper/ The original Python scraper and its output (for reference)
```

Adding a board: one line (department + board) in `DEPT_BOARDS` in `scrape/Scraper.kt`.

## Tests

```sh
./gradlew test
```

## Deployment (AWS EC2 + Docker)

SQLite needs a persistent disk, so use EC2 (or a Lightsail instance). Since it's a JVM server, `t3.small` (2 GB) or larger is recommended.

```sh
./gradlew fatJar
docker build -t cuk-moa-ktor .
docker run -d --name cuk-moa --restart unless-stopped --env-file .env \
  -p 80:8080 -v /srv/cuk-moa:/data cuk-moa-ktor
curl http://<server address>/health
```

Put HTTPS in front of the server (Caddy/nginx + Let's Encrypt, or ALB + ACM) before shipping the app.
