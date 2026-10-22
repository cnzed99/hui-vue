# Database and runtime baseline — 2026-10-22

This baseline runs on the purchased server only. Backend Java has not been started by the database task; source code is unchanged.

## Initialized data

- Extracted 43 `system_*` and `infra_*` CREATE TABLE statements from pinned upstream SQL, removed historical AUTO_INCREMENT values. Did not execute DROP statements or any upstream INSERT. Five sample business tables were excluded.
- Created isolated `print_demo` database and loopback-only `print_app` runtime user with SELECT/INSERT/UPDATE/DELETE only. Schema changes require separate privileged migration. The database initializer refuses reruns against an existing database/secrets file rather than overwriting work.
- Added one synthetic tenant, one synthetic store department, `tenant_admin` (not unrestricted `super_admin`) and restricted-reader roles, three synthetic admin/reader/disabled accounts, and one fresh default OAuth client. Initial menu permits only user list/query for baseline permission checks. Print-domain menus/actions must be added in later migrations.
- Passwords are random 16 characters, conforming to current AuthLoginReqVO. BCrypt cost 10 was checked in memory. Actual plaintext credentials, client secret and password hashes are not in initializer/schema sources or evidence.
- No external file-storage, SMS, mail, payment, social-client or scheduler samples imported. Registration is disabled because `system.user.register-enabled=true` is absent; this still needs HTTP verification after startup.

## Runtime files

- `ops/runtime/start-backend.sh`: discovers exactly one executable target jar, sources private environment, starts cloud profile with explicit external config. Java heap 768 MiB / metaspace 384 MiB; execution/footprint still must be verified.
- `ops/runtime/application-cloud.yaml`: MySQL 3306 / Redis 6379, backend 48080, all loopback. Admin UI is loopback 18080. Mock authentication off, real tenancy on, API encryption/captcha off for this isolated demo; multipart reception remains disabled until controlled file access is implemented.
- `ops/runtime/logback-cloud.xml`: 10 MiB files, seven days, 100 MiB compressed archive cap. Console output remains available for bootstrap errors; long-running console/journal retention must also be bounded by deployment supervisor.
- `runtime/secrets/runtime.env`, `runtime/secrets/demo-accounts.json`, `runtime/secrets/mysql-app.cnf`: server-generated secrets, directory 0700 / files 0600; never print, commit, or include in ordinary source archive. Redis also has root:redis 0640 `/etc/redis/print-system.conf`.
- `ops/runtime/safe_log.py evidence/backend-startup.log --lines 100`: use this log reader before displaying startup or runtime logs; actual runtime secrets, common credential fields, BCrypt values, bearer tokens and JWTs are redacted in server memory. Do not directly cat secret files.

## Verified

- Schema contains 43 tables; real app-user insert/select transaction and rollback succeeded, leaving no test record.
- App-user CREATE TABLE and mysql.user reads rejected.
- Redis authenticated PING succeeded; unauthenticated request returned NOAUTH. Redis memory cap 128 MiB, noeviction; MySQL buffer pool 128 MiB, connections 40.
- `ss -lntH` verified four DB/Redis listener sockets exclusively on loopback.
- Launcher bash syntax, logging XML parsing and log-redaction checks passed.
- Disk after setup ~49 GiB free. Temporary Redis private-config staging file removed after installation; no raw seed SQL containing credentials was persisted. Necessary caches/source/data retained.

## Not verified

Backend startup, Spring effective profile, API login/logout/permission enforcement, tenant boundary tests, file access controls, UI integration, process restart/backup restore, and user acceptance remain unverified. Config preparation is not runtime acceptance. Evidence: `evidence/database/initialization.json` and `service-checks.json`.
