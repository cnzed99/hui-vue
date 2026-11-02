# M8 Nginx candidate

Status: `CANDIDATE_NOT_INSTALLED`. This directory does not activate cancellation writes.

`nginx-print-system.conf` preserves the complete M7 server configuration and adds only:

- Exact GET `/app-api/print/miniapp/demo/cancellation`.
- Exact POST `/app-api/print/miniapp/demo/cancellations`, with a 1KiB body limit and the existing `print_miniapp_quote_rate` write-rate zone.

Other methods return 403 before proxying; adjacent `/app-api/` paths retain the 404 fallback. Both routes retain no-store, pragma and security response headers. Host is set to `$host`, Authorization is forwarded explicitly, and Nginx's default request-header forwarding preserves Origin (verified by the isolated probe).

The file is a server snippet, like M7. The installed HTTP-level `print_miniapp_upload`, `print_miniapp_upload_rate` and `print_miniapp_quote_rate` zones remain required; this candidate does not redefine them.

Run the isolated check with:

```sh
python3 ops/runtime/candidates/m8/probe-nginx.py
```

The probe loads the full candidate under a temporary main Nginx configuration. It replaces only its loopback listener and upstream port, starts a mock HTTP upstream, and never forwards traffic to the application backend. It runs `nginx -t`, checks exact routes/methods, adjacent routes, the 1024/1025-byte boundary, rate limiting, request headers and no-store responses. It compares the installed Nginx configuration hash and service states before/after, then stops only its own temporary Nginx/mock processes and removes its own scratch directory.

Raw results are written to `/home/ubuntu/print-system/evidence/testing/m8-nginx-candidate/`. These tests prove proxy behavior against a mock upstream. They do not prove cancellation business transactions or authorize runtime installation; final Astra High review is still required.
