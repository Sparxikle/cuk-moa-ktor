-- CareerNet job details per job name (JobInfo as JSON). summary is null when CareerNet had no match.
CREATE TABLE job_info (
    name       TEXT PRIMARY KEY,
    data       TEXT NOT NULL,
    fetched_at TEXT NOT NULL
);
