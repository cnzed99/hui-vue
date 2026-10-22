# Auth smoke execution

Run only on the user cloud server after the actual backend is healthy. All tested paths are taken from the checked Yudao AuthController and WebProperties; see docs/acceptance/phase1-acceptance-plan.md.

Read-only negative probes:

```sh
python3 ops/acceptance/auth_smoke.py --tenant-id "$ACTUAL_TENANT_ID"
```

For positive login, place the actual synthetic test account in a server-local private JSON file (owner is the executing user, mode 600; do not commit or print its contents). Schema: username string, password string, tenantId integer. Then:

```sh
python3 ops/acceptance/auth_smoke.py --allow-login --credentials "$PRIVATE_AUTH_JSON"
```

This creates and revokes a test session. It deliberately avoids raw responses, usernames, passwords and tokens in reports. Output is pass/fail/not-run counts; full non-secret case results default to evidence/acceptance/auth-smoke.json. Read the report rather than treating exit 0 as full acceptance. Default mode leaves positive session tests NOT_RUN.

The script refuses non-loopback targets and redirects. It reads the source-verified CommonResult.code=401 as denial even if HTTP is 200. It does not treat arbitrary 4xx, tenant-not-found or server errors as authentication success.

This does not verify expired refresh tokens, action permissions, cross-store ownership, data persistence, files, pricing, orders, simulation safety, browser rendering, visual parity, or user acceptance. Those remain separate plan cases. Do not use this tool's output to close those tasks.

Validation performed when authored: Python AST parsing and CLI --help only; HTTP checks NOT_RUN. Do not put real secrets in this directory or append shell commands containing passwords to evidence logs. Preserve server runtime secrets outside version-controlled files.
