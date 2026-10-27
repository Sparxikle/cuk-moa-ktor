-- Classes opened per semester (OpenClass as JSON). Imported by hand from the school's 개설과목 export, replaced per semester.
CREATE TABLE open_classes (
    year        INTEGER NOT NULL,
    semester    TEXT    NOT NULL,
    subject_no  TEXT    NOT NULL,
    class_no    TEXT    NOT NULL,
    department  TEXT    NOT NULL,
    category    TEXT    NOT NULL,
    grade       INTEGER,
    name        TEXT    NOT NULL,
    data        TEXT    NOT NULL,   -- OpenClass JSON
    imported_at TEXT    NOT NULL,
    PRIMARY KEY (year, semester, subject_no, class_no)
);
CREATE INDEX open_classes_department ON open_classes (year, semester, department);
