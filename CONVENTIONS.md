# Conventions — server (cuk-moa-ktor)

Team 404's rules for this repo. Commit and Kotlin style are the same as the app repo ([cuk-moa-kmp/CONVENTIONS.md](https://github.com/Sparxikle/cuk-moa-kmp/blob/main/CONVENTIONS.md)); this file adds the server's own rules.

## Git

- **One branch, `main`.** Each finished change is one commit, with tests passing (`./gradlew test`).
- **Commit messages:** an English subject in the imperative or as a short result, capitalised, no trailing period, about 70 characters at most. A blank line, then a body explaining why.
- **Never committed:** `.env` (API keys), `data/` (the SQLite database), build output. Configuration examples go in `.env.example`.
- API model changes are made in the app repo's `shared/` module and committed together with the server change that uses them.

## API

- **The models are shared.** Responses and requests use the classes in the app repo's `shared/` module (`kr.ac.catholic.cukmoa:shared`), so a field the app can't read breaks the server build first.
- **Paths** are `/api/v1/<plural noun>` with `GET` for reading; the only write is `POST /api/v1/timetables`. Query values with several items are comma-separated (`?major=csie,ai`).
- **Errors** always use `{ "error": { "code", "message" } }` with a code from `ErrorCodes` (`INVALID_REQUEST`, `NOT_FOUND`, `COURSE_NOT_FOUND`, `ANNOUNCEMENT_NOT_FOUND`, `AI_UNAVAILABLE`, `RATE_LIMITED`, `INTERNAL_ERROR`). Messages are Korean, for students.
- **The spec is part of the code.** Every route in `Application.kt` is described in `src/main/resources/openapi/documentation.yaml` (served at `/swagger`), with its parameters, response schema and errors. `OpenApiTest` fails when the two disagree, so a route change and its spec change land in the same commit.
- No student data is stored. The timetable request carries the student's state only for that request.

## Code layout

```
Application.kt        Config, routes (module()), scheduler, command-line tools
db/Database.kt        SQLite access; schema changes as numbered migrations in resources/db/NNNN_name.sql
scrape/               Collectors: Scraper (schedule, politeness) and Parsers (HTML → rows)
Departments.kt …      One file per data source or feature (departments, jobs, timetables, site registry)
sites/                department-sites.json: the registry of department boards, professor pages and roadmaps
deploy/               Production systemd unit and Caddyfile
```

- **Migrations** are never edited after they ship; add the next number instead.
- **Collectors** wait `requestDelay` (0.3 s) between requests, record each source's last success and error in `scrape_runs`, and never delete stored data when a site fails.
- **Parsers** are tested with saved real pages in `src/test/resources/fixtures/` (one HTML file per page layout). When a school site changes, update the fixture and the parser together.
- **Configuration** comes from environment variables, then `.env`. Secrets only there; AWS credentials come from the instance role, never from a file.

## Kotlin style

Kotlin official code style, English KDoc on every public or non-obvious declaration (why, not what), `camelCase`/`PascalCase` names, and slow work off Ktor's request threads: calls to outside APIs (Bedrock, CareerNet) run in `withContext(Dispatchers.IO)`. SQLite reads in routes are short indexed queries.

## Checks and deployment

```sh
./gradlew test      # parsers on real HTML, API responses with the shared models, route ↔ OpenAPI spec, timetables, jobs
./gradlew fatJar    # build/libs/server-all.jar
```

Deploy only after `./gradlew test` passes, following README → Deployment: copy the jar, keep a dated `.bak` of the old one, restart the `cuk-moa` service, then check `/health` and `/swagger` over HTTPS.
