CREATE TABLE announcements (
    id          INTEGER PRIMARY KEY,
    source      TEXT NOT NULL,              -- lms | main | event | outside | dept
    board       TEXT NOT NULL,              -- raw board slug (notice, career, contest, ...)
    major       TEXT NOT NULL DEFAULT '',   -- csie | mtc | datascience | ice | ai; '' when not dept (NULLs would break UNIQUE)
    category    TEXT,                       -- normalized category (main site: its own category text)
    article_no  TEXT NOT NULL,              -- articleNo / ARTL_NUM parsed from the link
    title       TEXT NOT NULL,
    date        TEXT NOT NULL,              -- YYYY-MM-DD
    author      TEXT,
    views       INTEGER,
    link        TEXT NOT NULL,
    image       TEXT,
    pinned      INTEGER NOT NULL DEFAULT 0,
    first_seen  TEXT NOT NULL,              -- UTC, YYYY-MM-DDTHH:MM:SSZ
    updated_at  TEXT NOT NULL,
    UNIQUE (source, major, board, article_no)
);

CREATE INDEX idx_announcements_date ON announcements (date DESC);
CREATE INDEX idx_announcements_filter ON announcements (source, major, category);
CREATE INDEX idx_announcements_first_seen ON announcements (first_seen);

-- Trigram tokenizer so Korean substrings match without word segmentation
CREATE VIRTUAL TABLE announcements_fts USING fts5(
    title,
    content = 'announcements',
    content_rowid = 'id',
    tokenize = 'trigram'
);

CREATE TRIGGER announcements_ai AFTER INSERT ON announcements BEGIN
    INSERT INTO announcements_fts (rowid, title) VALUES (new.id, new.title);
END;

CREATE TRIGGER announcements_ad AFTER DELETE ON announcements BEGIN
    INSERT INTO announcements_fts (announcements_fts, rowid, title) VALUES ('delete', old.id, old.title);
END;

CREATE TRIGGER announcements_au AFTER UPDATE OF title ON announcements BEGIN
    INSERT INTO announcements_fts (announcements_fts, rowid, title) VALUES ('delete', old.id, old.title);
    INSERT INTO announcements_fts (rowid, title) VALUES (new.id, new.title);
END;

CREATE TABLE scrape_runs (
    board_key   TEXT PRIMARY KEY,
    last_ok     TEXT,
    last_error  TEXT,
    item_count  INTEGER
);
