# Print staff/store scope foundation

## Server contract

Package `cn.iocoder.yudao.server.print.scope`:

- `PrintScope(long tenantId, long storeId, long userId)` is server-created only. Never bind it to request-body/query data.
- `@Service PrintScopeService.requireCurrent()` resolves the current authenticated staff's one active database mapping. It rejects missing identity, non-staff actor types, missing/mismatched tenant context, cross-tenant visit overrides, disabled/deleted users, missing/disabled/ambiguous mappings, and unavailable stores.
- `PrintContext` adds `storeName` and string `actorType`, currently `STAFF` only. CUSTOMER/TERMINAL enum values reserve a future adapter boundary; they do not grant access or reuse staff identities.
- `PrintContextController` in `...print.controller.admin`: `GET /admin-api/print/context`, no request scope parameters, returns `CommonResult<PrintContext>`. It is not PermitAll. A bound authenticated employee can query their own context without an additional print permission.
- Token's tenant owner must equal `TenantContextHolder` and any visit tenant. Framework cross-tenant visit shortcuts are explicitly rejected here.
- All JdbcTemplate queries are parameterized. Employee mapping queries filter tenant+user, and store validation filters tenant+store. Downstream file/order/quote queries must also include BOTH `scope.tenantId()` and `scope.storeId()`; permission annotations are additional checks, not a replacement for ownership filtering.
- Current DTO IDs are Java long; frontends must not silently lose precision if future IDs exceed JavaScript's safe integer range.

## Errors

ServiceException integrates with existing CommonResult handler. Scope owns `1_510_000_001..099`:

|Code|Meaning|
|---|---|
|1510000001|Login identity missing/invalid|
|1510000002|Tenant context missing|
|1510000003|Forbidden staff/tenant/mapping context|
|1510000004|No staff store assignment|
|1510000005|More than one active store mapping; no arbitrary first-store fallback|
|1510000006|Actor type not implemented|
|1510000007|Store missing, deleted or disabled|

An unauthenticated HTTP request can be rejected by the framework as code401 before reaching the service. HTTP status alone is not sufficient; inspect CommonResult.code.

## Tables and migration

`print_store`: global id PK, tenant_id, store_code, name, status(0 active/1 disabled), deleted and creator/create_time/updater/update_time. Unique tenant+code and tenant+id. `print_staff_store`: id, tenant_id, store_id, user_id, status/deleted and auditing. Unique tenant+store+user; FK tenant+store to print_store prevents cross-tenant store references. Index tenant+user+status+deleted supports scope resolution. No customers or terminals are inserted as staff.

Current phase permits one active store per employee. Multiple active rows are treated as a data ambiguity and rejected rather than silently selecting a row. The database permits future multi-store membership records; a future explicit trusted current-store selection contract is required before enabling that behavior.

Apply with `python3 ops/db/migrate_print_foundation.py`. It checks reserved IDs/identities, requires >=10 GiB free, creates an encrypted verified pre-migration backup, applies additive SQL and verifies idempotent repetition. No original table is dropped. Existing staff mappings/menu records are not overwritten or re-enabled. Unexpected reserved identities stop the migration. This bootstrap's exact seed-count assertions target the phase-one single-store fixture; later expansion needs its own versioned migrations.

The first SQL application completed, then a TSV parser incorrectly removed an empty trailing path in the repeated-run guard and stopped verification. Corrected to strip newline only; rerunning preserved all data and passed. Initial pre-change backup has43 tables; retry backup has45. Both are documented in evidence, not mislabeled as a rollback execution.

Initial synthetic records: tenant1/store1 `DEMO001` named 演示门店; admin and reader mapped to store1, disabled account unbound. New menu root2000 plus buttons2001..2005: file query/upload/delete and quote create/query. Tenant administrator gets all6 records; reader gets root/file-query/quote-query only. No super_admin. Menu root is a permission container; it does not claim an implemented business page.

## Verification boundaries

Executed migration, schema/seed counts and unchanged repeat-run counts; Java whitespace and migration Python syntax checked. Actual executable jar includes spring-jdbc6.2.19, so no dependency/POM edit was needed. No Maven compile, restart or HTTP call was performed by this task. Root must compile all parallel changes into one immutable release, then validate context, permissions, missing/disabled mapping, cross-tenant inputs and ambiguous-store fixtures with explicit cleanup.

Evidence: `evidence/database/print-foundation-v1.json`. Current source: six Java files under scope and PrintContextController. SQL and script contain no real passwords/hashes/tokens.

## Prepared HTTP fixture checks (not executed by this task)

`python3 ops/acceptance/print_scope_smoke.py --allow-fixture-mutations` uses private mode0600 `runtime/secrets/smoke-admin.json` and `smoke-reader.json`. Run only after the root deploys the combined release, sequentially with other fixture/DB tasks. The opt-in flag is required because it temporarily changes the dedicated demo mapping/store and inserts owned fixtures.

Checks anonymous rejection, admin and reader identity, ignored request scope overrides, temporarily disabled/deleted mapping rejection, disabled-store rejection, ambiguous active stores, and a real enabled second-tenant/store fixture. Cross-tenant rejection must be403 or1510000003; a500 is not accepted as an isolation pass. The second-tenant test proves the existing admin cannot switch context to a real other tenant/store, not a full two-user business-record authorization audit. Temporary rows are tagged with unique creator markers, exact owned IDs are deleted in finally, original statuses restored and sessions logged out. Audit logs and normal AUTO_INCREMENT gaps may remain as truthful test traces. Output/evidence contains check names and booleans only, never tokens/passwords or response bodies. Script was syntax-parsed but not run.
