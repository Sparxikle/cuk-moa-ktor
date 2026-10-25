-- Official department major roadmaps (MajorRoadmap as JSON). Replaced wholesale per department.
CREATE TABLE major_roadmaps (
    major      TEXT PRIMARY KEY,   -- Department board code
    data       TEXT NOT NULL,      -- MajorRoadmap JSON
    updated_at TEXT NOT NULL
);
