BEGIN TRANSACTION;
CREATE TABLE budget_accounts (
                account TEXT PRIMARY KEY, mode TEXT NOT NULL, shares TEXT NOT NULL);
INSERT INTO "budget_accounts" VALUES('legacy','subscription','{"weekly": 5000}');
CREATE TABLE budget_allocations (
                account TEXT NOT NULL, run_id TEXT NOT NULL,
                period TEXT NOT NULL, start INTEGER NOT NULL, reserved INTEGER NOT NULL,
                PRIMARY KEY(account,run_id,period),
                FOREIGN KEY(account,run_id) REFERENCES budget_runs(account,run_id),
                FOREIGN KEY(account,period,start) REFERENCES budget_windows(account,period,start));
INSERT INTO "budget_allocations" VALUES('legacy','held','weekly',100,500);
CREATE TABLE budget_runs (
                account TEXT NOT NULL REFERENCES budget_accounts(account),
                run_id TEXT NOT NULL, amounts TEXT NOT NULL, state TEXT NOT NULL,
                actual TEXT, PRIMARY KEY(account,run_id));
INSERT INTO "budget_runs" VALUES('legacy','held','{"weekly": 500}','reserved',NULL);
CREATE TABLE budget_windows (
                account TEXT NOT NULL REFERENCES budget_accounts(account),
                period TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
                total INTEGER NOT NULL, share INTEGER NOT NULL, used INTEGER NOT NULL,
                observed_used INTEGER, observed_at INTEGER, max_age INTEGER NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY(account,period,start));
INSERT INTO "budget_windows" VALUES('legacy','weekly',100,200,10000,5000,100,100,100,300,'manual');
COMMIT;