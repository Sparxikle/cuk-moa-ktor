-- School academic calendar (www.catholic.ac.kr academic calendar board). Replaced wholesale per academic year.
CREATE TABLE calendar_events (
    id             INTEGER PRIMARY KEY,
    academic_year  INTEGER NOT NULL,
    title          TEXT NOT NULL,
    start_date     TEXT NOT NULL,   -- YYYY-MM-DD
    end_date       TEXT,            -- YYYY-MM-DD, NULL for single-day events
    campuses       TEXT NOT NULL    -- "성심,성의" (empty string means no campus label)
);
CREATE INDEX idx_calendar_start ON calendar_events (start_date);
