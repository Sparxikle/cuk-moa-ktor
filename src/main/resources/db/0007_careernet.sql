-- The whole CareerNet job encyclopedia (552 jobs as of 2026-11), one raw jobs.json item per row, refreshed every 30 days.
-- Department career names are matched against it locally, so the per-name cache (job_info) is no longer needed.
DROP TABLE IF EXISTS job_info;
CREATE TABLE careernet_jobs (
    job_cd     TEXT PRIMARY KEY,
    data       TEXT NOT NULL,
    fetched_at TEXT NOT NULL
);
