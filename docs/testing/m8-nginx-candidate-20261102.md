# M8 Nginx candidate verification (2026-11-02)

Implementation model: GPT-6.1 Sol High. Status: `PASS_PROXY_CANDIDATE_ONLY`.

Candidate SHA-256: `bb16af7b9c477372e13ee681d72d16362a5be72cf947d270febc5e5b350a2488`.

The M8 candidate is the complete M7 server snippet plus two exact cancellation routes. No running configuration was installed or reloaded.

Isolated probe: **26/26 PASS**. The full candidate passed `nginx -t` under a temporary main configuration and loopback listener. A mock upstream confirmed query, Host, Origin and Authorization forwarding, GET/POST no-store responses, and a 1024-byte POST body. Unsupported methods returned 403 without reaching upstream; adjacent paths returned 404. A 1025-byte POST returned 413 before upstream; repeated writes reached the existing 30 requests/minute zone's 429 limit.

The installed Nginx configuration hash remained `42e2d578b5c00696833b96e590ad9bd177ceed43588af3f6fb9b6acf22698ab6` before/after. Nginx and backend remained active. Temporary Nginx/mock processes stopped and scratch was removed.

Raw evidence:

- `/home/ubuntu/print-system/evidence/testing/m8-nginx-candidate/nginx-test.log`
- `/home/ubuntu/print-system/evidence/testing/m8-nginx-candidate/result.json`
- Repository copy: `docs/testing/m8-nginx-candidate-20261102.json`.

This result proves candidate proxy behavior against a mock upstream. Java+MySQL business integration, final Astra High approval and runtime deployment are separate gates.
