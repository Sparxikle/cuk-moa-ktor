-- Professor profiles from department websites (FacultyMember as JSON). Replaced wholesale on every collection.
CREATE TABLE faculty (
    id         TEXT PRIMARY KEY,   -- Faculty number (persNo)
    data       TEXT NOT NULL,      -- FacultyMember JSON
    updated_at TEXT NOT NULL
);
