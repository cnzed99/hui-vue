# Backend standalone print checks

The fixed allowlist in `ops/testing/backend-print-checks.json` contains the 16 existing synthetic `main` checks at backend commit `f9d6d912993643b69462e5c091aecbabf02bb2f2`. It also accounts for the non-executable lock fixture and explicitly excludes `ProjectReactor`, whose main rewrites a project. The runner validates the entire 18-source inventory before executing any check.

Each selected check runs in its own JVM, working directory and temporary/home directory. The runner uses a 512MiB Java heap, 192MiB metaspace limit, serial GC, per-case timeout, CPU and file-descriptor limits, disabled core dumps, and a new process group. It terminates the complete process group before removing the case directory. An enforced whole-runner cgroup caps memory at 3GiB and swap at 256MiB. The standard build-window lock serializes the run and stops/restores the four services.

The runner accepts `--group core|filesystem|render|all`; the default `core` selects nine memory/recording-port checks. `all` selects all 16. Missing preconditions produce `SKIP_WITH_REASON`, subsequent cases stop, and an all-16 PASS cannot be reported. A check requires both exit code zero and its success marker. A timeout, failure or missing marker stops the remaining cases while preserving raw evidence.

Inputs are pinned by backend commit, clean worktree, runtime-JAR SHA and approved-font SHA. The classpath uses the exact compiled test/main classes, only `BOOT-INF/lib` entries from that JAR, and the cached Mockito/agent test dependencies. The runner confirms compiled main files match the JAR and records test-class, main-class, library and font hashes. Tests do not start the application or connect to business SQL, Redis, SMTP or HTTP. Synthetic file/renderer inputs stay in the build-window scratch.

Example invocation, with a fresh evidence directory and unit/label per attempt:

```sh
ENGINEERING=/home/ubuntu/print-system/runtime/worktrees/r11-backend-checks-engineering-20261103
bash "$ENGINEERING/ops/run-safe-build-window.sh" r11-backend-checks-all-r2 -- \
  /bin/bash -c 'exec sudo -n systemd-run --quiet --scope \
  --unit=print-r11-backend-checks-all-r2 \
  -p MemoryMax=3221225472 -p MemorySwapMax=268435456 \
  /usr/sbin/runuser -u ubuntu -- /usr/bin/env PATH=/usr/bin:/bin \
  HOME=/home/ubuntu LANG=C.UTF-8 TMPDIR="$TMPDIR" \
  /usr/bin/python3 /home/ubuntu/print-system/runtime/worktrees/r11-backend-checks-engineering-20261103/ops/testing/run-backend-print-checks.py \
  --backend /home/ubuntu/print-system/runtime/worktrees/backend-test-compile-20261102 \
  --evidence /home/ubuntu/print-system/evidence/testing/NEW_UNIQUE_RUN \
  --group all'
```

After the safe window completes, separately require four `active` service results and HTTP 200 with business `code=401` from `/admin-api/system/auth/get-permission-info`. The safe-window helper's transport-only curl check is not sufficient by itself; each archived attempt includes this additional `health.json` and the window result.

## Executed result on 2026-11-03

- [First attempt](../../evidence/testing/r11-backend-checks-all-20261103/summary.json): `PARTIAL`. The first 13 cases passed. The collage renderer itself exited zero and returned PASS with **34** assertions, while the new manifest had copied the historical count **33**; therefore its marker did not match. The last two cases were correctly `NOT_RUN_PREVIOUS_FAILURE`. Raw logs are unchanged.
- [Second attempt](../../evidence/testing/r11-backend-checks-all-r2-20261103/summary.json): **PASS, all 16 checks**. The sole runner correction was the collage success-marker count 33 to 34. The backend source and JAR were unchanged. The complete rerun passed, including real OS file locks, staged upload, image/collage process renderers, approved-font text rendering and the original demo library.
- Backend JAR: `e16da5d2da357ba36020e7ef79376f1b61003d75dd0974f7525887b1f0eb0c67`. The final check processes took 49.578 seconds combined; largest recorded per-process `/usr/bin/time` RSS was 377808KiB. This resource figure is not an aggregate cgroup memory peak.
- Both windows recovered all services, HTTP200/code401 passed, and per-case/build scratch was removed. Approximately 39GiB remained available.

Implementation model: `gpt-6.1-sol`; effort inherited and not separately exposed. No critical rule changed, so this runner batch did not trigger Astra High. Independent ordinary review is pending coordinator closeout.

This result proves the listed standalone synthetic checks for the pinned backend candidate. It does not execute upstream reactor JUnit tests, SQL/Redis/HTTP integration, WeChat device checks, user acceptance, real payment or real printing. R11 full-regression and management-end full type-check gates remain separate.
