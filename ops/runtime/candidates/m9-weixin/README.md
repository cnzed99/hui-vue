# M9 Weixin staged upload candidate

Status: CANDIDATE_NOT_DEPLOYED. Native Wechat remains disabled; no AppID is included.

- Backend: `71e22c43606a620bc8ab9f1d100ff3630013e6ad`.
- Miniapp: `b44e5fa2ab6a404519654f595342cb958981ea40`.
- Full build evidence: `/home/ubuntu/print-system/evidence/build/m9-weixin-full/`.
- Source verification: `python3 ops/acceptance/m9_weixin_source_check.py`.
- Deploy only after final independent review and authorization: `bash ops/runtime/deploy-m9-weixin-upload.sh --apply`.
- Explicit restore: `bash ops/runtime/rollback-m9-weixin-upload.sh --backup /home/ubuntu/print-system/runtime/backups/m9-weixin-RUN_ID`.

Deployment retains the existing H5 M9 dropin, installs the new candidate with staged/native disabled, publishes only frozen jar/H5 artifacts, waits for backend readiness and runs PDF/single-image/H5 regression. Only then it enables staged uploads, leaving native disabled, and runs synthetic HTTP regression. Any failure or signal restores prior jar/H5 links, nginx and staged dropin, restarts and waits for readiness. Prior immutable releases and backups are retained. No database migration is required. Application-owned staging cleanup runs under the backend storage lock; deployment never deletes active image scratch.

The Nginx staged regex routes require the generic `/app-api/` fallback to be a plain prefix, since `^~` would suppress regex routing. UUID staged paths disable access logging. Original H5 and new part routes share the same global/IP body-buffer budgets. Only staged requests use the new 60r/m rate zone.

HTTP fixture smoke includes existing H5 downstream simulated print loop, staged create/part/complete replay, manifest conflict, cross-principal hidden operations, missing-part rejection, same-key H5/staged equivalence, new-session database replay and ordered-content conflict, native disabled and bad/empty Origin. Smoke emits only synthetic pass flags and existing H5 smoke identifiers; no tokens, filenames, upload UUIDs or original-image hashes are logged. These scripts have not been executed against this candidate runtime yet. Correct native AppID/version/Host acceptance requires an enabled isolated fixture or later authorized integration and is not claimed by disabled-channel checks.

Wechat devtools/device, real payment/printing and external user acceptance remain NOT_RUN. Static checks and nginx -t do not establish runtime concurrency, low-disk, shutdown or edge contention acceptance.

Post-enable deployment also requires `m9_weixin_edge_admission_smoke.py`: a fresh loopback source sends create + nine single parts + complete as eleven direct requests without 429 retry, then waits for READY(pageCount=1). Separate bound loopback sockets hold incomplete H5 and part bodies to prove shared per-IP=1 and global=2 admission. The third H5 and part requests from another IP must both return 429; closing holders must release capacity. All sockets close in finally; no child process or local image fixture is created. A failure remains inside the deployment EXIT rollback guard. Runtime results remain NOT_RUN until authorized deployment.
