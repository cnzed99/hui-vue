# CORE-01 private PDF foundation

The initial scope is one application process, one persistent database queue and a private storage directory. Owner, store and tenant are resolved from the authenticated staff identity. Metadata/content/delete requests cannot supply another owner. File content is outside Nginx's static root. Generic upstream infra file endpoints are disabled in the cloud profile.

## Current evidence
- PDFBox 3.0.8 dependency is locked, with its official source API verified before implementation.
- Maven packaging passed after correcting the repository class's compatibility with Spring's class proxying.
- The immutable release passed actual login, permission-info and logout checks before publication retention ran.
- `print_scope_smoke.py`: 14 checks passed, including actual temporary second tenant/store fixtures, invalid store mapping and exact cleanup.
- `pdf_parser_checks.py`: 8 parser checks passed on synthetic PDFs; includes encrypted, malformed, zero-page and over-limit documents. Temporary files removed.
- `file_disabled_smoke.py`: 6 gate/permission checks and 2 session-revocation checks passed, zero uploaded records.
- `print_file` schema migrated after encrypted database backup; 18 columns and scoped idempotency unique index checked.

## Intentional disabled state and remaining work
`print.files.enabled=false` and multipart uploads remain disabled. Max bytes, pages and retention require explicit configuration; proposed commercial limits have not been confirmed. Synthetic parser test limits are not business policy decisions.

The current implementation includes a durable upload/processing/ready/failed/deleted state model, scoped idempotency, owner-protected download, startup recovery and targeted blob cleanup. Enabled synthetic HTTP integration, idempotency replay races, actual process restarts and configured reserve rejection have now been checked as detailed below. End-to-end order acceptance, actual disk exhaustion, hard parser time limits and process isolation remain unfinished. Do not treat parser unit evidence as production file safety approval or CORE-01 completion.

Next: connect confirmed pricing and order state contracts, then cover remaining parser and concurrency risks. Keep the normal demo upload gate off until the relevant policy is decided.

## Enabled synthetic HTTP acceptance
`python3 ops/acceptance/file_http_smoke.py` ran a temporary loopback instance on port 48081 while the normal 48080 demo stayed disabled. The test configuration was 2 MiB / 10 pages / 3600 seconds; these are test fixtures, not adopted business limits. All 32 checks passed, including actual upload/download, scoped access, idempotency races, corrupt/encrypted rejection, filename cleanup, oversize rejection, expiry/delete blob cleanup and configured disk-reserve rejection.

Actual process restarts preserved READY file bytes and metadata. Interrupted-processing recovery was checked with an explicitly seeded PROCESSING row while the process was stopped; it is not a forced mid-parser crash experiment. Tokens were logged out, all owned rows and temporary storage removed, and the temporary instance stopped. Evidence: `evidence/acceptance/file-http-smoke.json`; earlier 26-check evidence retained as `file-http-smoke-v1.json`.

Remaining: production upload policy, actual order linkage, parser hard timeout/process isolation, full conversion-format matrix, signed share/download lifetime if required, and broader pressure/race scenarios. CORE-01 remains in progress.
