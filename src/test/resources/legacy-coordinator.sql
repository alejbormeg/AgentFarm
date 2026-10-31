BEGIN TRANSACTION;
CREATE TABLE runs (
            id INTEGER PRIMARY KEY, project TEXT, task TEXT, hash TEXT,
            started TEXT, mode TEXT, result TEXT);
CREATE TABLE tasks (
            project TEXT, id TEXT, spec TEXT NOT NULL, hash TEXT NOT NULL,
            state TEXT NOT NULL, PRIMARY KEY(project, id));
INSERT INTO "tasks" VALUES('demo','legacy','{"acceptance": ["Evidence"], "allowed_paths": ["docs/"], "id": "legacy", "project": "demo", "title": "Migración ✓"}','ca2065d01508c11c5d936778976be7d573166fa307a8e9bcbfc4b1489dfae2c0','ready');
COMMIT;