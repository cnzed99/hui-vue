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

The current implementation includes a durable upload/processing/ready/failed/deleted state model, scoped idempotency, owner-protected download, startup recovery and targeted blob cleanup. These paths have not yet received enabled HTTP upload integration, process restart, race, disk pressure or end-to-end order acceptance. Hard parser time limits and process isolation are also unfinished. Do not treat parser unit evidence as production file safety approval or CORE-01 completion.

Next: run an explicitly isolated synthetic upload acceptance configuration, validate persistence/idempotency/owner denial/restart/expiry, then connect confirmed pricing and order state contracts. Keep the normal demo upload gate off until the relevant policy is decided.
