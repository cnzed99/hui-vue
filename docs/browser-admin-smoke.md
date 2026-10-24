# Deployed admin UI browser acceptance

Implementation: GPT-6.1 Sol High, parent-directed. Astra not invoked: ordinary browser acceptance consumes existing login and read contracts, with no business or authentication-rule changes.

## Scope
`ops/acceptance/browser_admin_smoke.mjs --allow-login` uses an isolated, non-persistent Playwright Chromium headless-shell context on the cloud server at `http://127.0.0.1:18080`.

It enters the protected orders URL while unauthenticated, verifies login redirect, fills the actual tenant/username/password form, unchecks Remember Me, submits the real Login button, verifies the deployed shell and actual orders HTTP503/code1510030000 disabled state, navigates to the workbench, compares real PrintScope user identity with actual permission-info identity in memory, checks the orders link, performs normal UI logout, then verifies the protected workbench again requires login.

This does not validate order-data lists/details, the unbuilt demo workflow, end-to-end operations, original-site visual parity, real payments, terminal receipts or physical printing. It does not set localStorage/cookies/token, forge login, call print writes or seed records. Authentication creates and revokes only this test session.

## Runtime and collection
- Node: `runtime/node/current/bin/node`; dedicated package `runtime/browser-tools` pins Playwright with its package-lock. Browser cache, temporary profile, native libraries, fonts and cache remain in that owned directory.
- Chromium headless shell only. Official installation reference: https://playwright.dev/docs/browsers#chromium-headless-shell
- Missing Ubuntu libraries are downloaded with `apt-get download` into the owned directory and extracted with `dpkg-deb --extract` into a private sysroot. No global package install, apt upgrade, service restart or system library overwrite. Chromium alone receives the private LD_LIBRARY_PATH and fontconfig configuration.
- Credentials are read only at runtime from owner-owned, exact0600, non-symlink `runtime/secrets/smoke-admin.json`. Only memory contains username/password; tenantName uses the optional field or the explicitly known Print Demo tenant. Credential values, tokens and payloads are absent from logs/reports.
- No HAR, trace, video, console collection or storage-state export. No screenshot on the login page or while a password input exists. Two post-login screenshots mask header identity, account and store-name fields. Errors serialize only a fixed stage and sanitized error type, never a Playwright message/call log that could contain typed input.
- Only same-origin traffic is permitted. Print/API business writes are blocked; only normal auth login/logout/refresh POST paths are allowed. Blocked-write count must remain zero.
- On failure, normal UI logout is attempted first. Only if it is not confirmed, the token from this run own successful login response and its actual request tenant header are used in memory for the existing exact logout endpoint. The original token is then checked against permission-info and must receive HTTP401 or CommonResult401. No browser token storage is read or injected. Only numeric response codes/counts and cleanup booleans are persisted. Unknown cleanup remains a failure. No other browser or user session is touched.

## Operation
From `/home/ubuntu/print-system`:

```sh
runtime/node/current/bin/node --check ops/acceptance/browser_admin_smoke.mjs
runtime/node/current/bin/node ops/acceptance/browser_admin_smoke.mjs --allow-login --attempt=2
```

Without flags, the script records NOT_RUN and does not launch a browser or read credentials. `--probe-browser` launches only an isolated about:blank page with all network blocked and never reads credentials; its separate report is under `evidence/acceptance/browser-runtime-preflight/` and is not application UI acceptance. Each real run creates `evidence/acceptance/browser-admin-smoke/attempt-N/` exclusively and refuses EEXIST, so earlier reports/screenshots cannot be overwritten. Login request/success counters and response codes are saved without bodies. Reports and masked screenshots are under that attempt directory. Actual result is `report.json`, bound to a SHA256 of the real served HTML document; source existence or dependency installation is not a browser PASS.

Before and after execution, all four application services and at least10GiB free disk are checked. After completion, remove only verified owned run/download temp contents and package-download caches; retain necessary browser, package-lock, private native sysroot/font and evidence for reproducibility. No frontend build, deployment, business DB modification or workflow-source edit belongs to this check.

## Verified runtime preparation (2026-10-23)
- Playwright1.64.1, Node22.23.4; Chromium headless shell156.0.8078.4/revision1248 and FFmpeg revision1013. Single official CDN download job completed; no duplicate full download or system package installation.
- 24 required native/font Debian packages were privately extracted. Full Chromium binary ldd with the private library path resolves every dependency.
- `--probe-browser`:3 checks PASS, completed=true, contextClosed=true, browserClosed=true,0 screenshots. Only an isolated about:blank page was opened with all network aborted; credentials were never read. This is runtime readiness, not real UI login acceptance.
- Necessary retained runtime files total330937608 bytes (about315.6MiB). Exact owned npm/deb/download/run temporary caches were removed after the installer ended; required package-lock/node_modules/browser/sysroot/fonts and evidence remain. Version/package/archive checksums and binary SHA256 are saved in `evidence/acceptance/browser-runtime-preflight/dependencies.json`.
- Four services active after cleanup; free disk49170186240 bytes (about45.8GiB). No frontend build, deployment, restart, business API writes or DB changes.
- Actual `--allow-login` remains NOT_RUN until root schedules it after the B HTTP job, to avoid shared 私有凭据文件指定的验收账号 permission-cache interference. New workflow is still unbuilt and has no real-browser acceptance from this task.

## First selector correction, before real attempt2
- Attempt1 is preserved under `evidence/acceptance/browser-admin-smoke/attempt-1/`. Its 4 pre-login checks passed, then the hidden Element Plus original checkbox input caused `getByRole(...).uncheck()` to time out before Login was clicked. No screenshot was captured.
- Nginx aggregate-only evidence over the exact attempt1 time window contains111 HeadlessChrome requests,0 POST login requests and therefore0 successful login responses. No test login session was created in that attempt. No original access-log lines or response bodies were retained.
- Credential-free diagnostics reproduce the original named uncheck failure, then verify a normal click on the real `label.el-checkbox` changes checked true to false. No credentials were read, no login was submitted, and no login screenshot was taken.
- The script now uses that actual label, has separate tenant/username/password/remember/submit/redirect stages, exclusively creates each attempt directory, captures only its own successful-login token/tenant header in memory, and verifies exact session revocation in finally. Real attempt2 requires root scheduling after B v4 terminates. This is selector repair round1, not a change to application authentication rules.

## Actual deployed UI attempt2 (2026-10-23)
Root scheduled one real run after the B job terminated. Frozen script SHA256 `cf8e45a01b8c10dcc894732a06e357ad4bbede14ca404e0cf7017366bc08c0a7` ran with `--allow-login --attempt=2`; PID414568 terminated with exit0. Report: `evidence/acceptance/browser-admin-smoke/attempt-2/report.json`.

20 checks PASS,0 FAIL. Actual form login request1, success1 (HTTP200/code0), refresh0. Normal UI logout succeeded, and the original token then received HTTP200/CommonResult401 from permission-info; own context and browser closed successfully. No exact fallback logout was needed. Print business write attempts0. Four services remained active and disk reserve passed before/after; post-cleanup evidence is `attempt-2/post-check.json`.

Verified deployed behavior: protected orders route redirects to real login; real tenant/user/password form plus actual remember-label click; login redirect preserves orders target; actual HTTP503/code1510030000 renders the disabled state without an empty-data result; workbench displays true store scope and matches the actual authenticated user identity; workbench orders navigation works; normal logout and post-logout protected route guard work.

Served HTML SHA256: `3fc5036a39bd3c9ac25a0aee4d96ff8d3ac70bfc06e17a5b70406b2bca7ac83b`. Two post-login screenshots only: `attempt-2/orders-disabled.png` and `attempt-2/workbench-context.png`; header identity/account/store-name fields are masked. No login screenshot, trace, HAR, video, console or payload log. Run log/exit/PID and screenshot hashes are retained in the immutable attempt2 evidence directory.

This PASS covers the deployed shell and feature-disabled path only. It does not establish an order-data page, new unbuilt workflow, full transaction flow, original-site visual parity, refund/real-payment behavior, terminal receipt or physical printing acceptance. The first checkbox selector issue is resolved on its first correction; no third repair round or Astra escalation was needed.
