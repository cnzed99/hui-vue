# 4 GiB cloud frontend build guard

Implementation: GPT-6.1 Sol High, implementing the Astra High approved design.
This probe-fix revision has only been uploaded and statically checked. No unit,
self-test, service stop/start, build, deployment, database or permission operation
was executed for this revision. Earlier real run `20261030T041418Z-e3adae5662a66590`
was reported FAIL; these changes do not rerun it or change that verdict.
An incremental Astra High review of these exact bytes is required before execution.

Root-owned location: `/home/ubuntu/print-system/ops/runtime/frontend-safe-build/`.
`launch.sh` requires root and an explicit mode; invocation without arguments exits 64.
Scripts must be root-owned and not writable by group/others. Runtime state is rooted
in `runs/<UTC>-<random16>/`; ubuntu can write only its unique scratch directory.

After review, run the harmless-to-four-services simulation tests first:

```sh
sudo /bin/bash /home/ubuntu/print-system/ops/runtime/frontend-safe-build/self-test.sh
```

These tests start only simulated child/controller/lock-keeper units; they never issue service
start/stop for mysql, redis-server, nginx or print-backend. They cover simulated
success (exit 0, retained active/exited unit and evidence, no Vite), exit 42, systemd
timeout, shared-lock rejection before controller/services/scratch, and simulated
recovery deadline failure with child clear/scratch removed/result persisted.
During a two-second simulated recovery window, the observer verifies that the
legacy shared lock is still held, then verifies release only after ExecStopPost
has finished. They also compare unchanged ActiveState/SubState snapshots.
Pure probe contracts additionally cover Redis PONG, explicit NOAUTH, inactive
service, connection refusal, timeout and unrelated Redis errors; nginx contract
tests require port 18080/login, HTTP 2xx/3xx and a successful curl exit.
The separate `self-test.sh --probe-contracts-only` entry calls only pure functions;
it does not query or modify services, start units, open network connections or load
Vite. It has not been executed for this static-only revision.
They do not prove real build
or service restoration. Do not execute them during static-only review.

After the tests and explicit real execution authorization:

```sh
sudo /bin/bash /home/ubuntu/print-system/ops/runtime/frontend-safe-build/launch.sh --real
```

The launcher returns a run id, state directory and controller unit. It is asynchronous.
Controller, child and lock keeper have separate systemd cgroups. The keeper acquires
`/home/ubuntu/print-system/runtime/build-tmp/.build.lock`, exactly the same flock
as `ops/run-build-batch.py`, BEFORE the controller is launched. Contention rejects
launch without any four-service action. It holds that descriptor continuously
until the controller unit, including ExecStopPost, has fully stopped. There is no
unlock/reacquire gap at recovery. Manual recovery acquires the same shared lock if
there is no live keeper; it refuses contention. The controller snapshots which
of the four services were active, stops only those, and launches a clean-environment
ubuntu child with fixed Node 22.23.4, heap 2048 MiB, Vite `build --mode cloud`, default
OXC, and unique scratch output. The child resolves actual Vite config first and fails
unless effective sourcemap=false, VITE_COMPRESS=none, minify=oxc, and no compression
plugin. External NODE_OPTIONS/VITE_* are excluded by `env -i`.

Child limits: MemoryHigh=2560M, MemoryMax=2816M, MemorySwapMax=512M,
CPUQuota=100%, Nice=10, RuntimeMaxSec=360, TimeoutStopSec=20,
KillMode=control-group, OOMPolicy=stop, RemainAfterExit=yes. Successful active/exited
is treated as completion, so Result/ExecMainStatus/limits/logs are captured before
recovery stops the child; failed units also remain available for evidence. No
running adjustment/swap expansion exists.
Every five seconds controller records child memory/current/swap/events and host
MemAvailable/SwapFree. Any sample below 256 MiB available or 512 MiB free swap stops
the child. Parent controller timeout is 480 seconds; ExecStopPost always invokes
recovery on ordinary exit/failure/timeout. This cannot survive host power loss.

Recovery retains the shared lock, stops child and verifies cgroup process lists
are empty before starting services or deleting scratch. It restores original-active
services in mysql → redis-server → nginx → print-backend order. MySQL socket ping and
an active Redis service plus PONG or explicit `NOAUTH Authentication required.`
must be observed before backend starts. Redis is probed with a clean environment
and no password; connection failures, timeouts and unrelated protocol errors fail.
NOAUTH is solely a listener/protocol/auth-gate readiness signal and does not prove
business authentication or application Redis access. It checks active service states,
nginx `http://127.0.0.1:18080/login` (only HTTP 2xx/3xx with successful curl exit)
and the existing backend permission-info endpoint's JSON API envelope
(code 0 or 401). An originally inactive service is never started. If database/Redis
readiness fails, backend start is blocked and recovery fails visibly. If dependencies
were originally inactive, backend may fail its health check; that is reported, not
converted into success. Services originally inactive remain outside recovery scope.

Recovery work uses a 420-second monotonic deadline; every service start is capped
at 45 seconds, each dependency readiness phase at 40 seconds, backend HTTP at 60
seconds, nginx HTTP at 10 seconds, and individual commands are capped by remaining
work time. Deadline expiration is a failure, never success. Final status snapshot
is capped at 32 seconds including kill allowances, scratch deletion at 45 seconds
plus 5 seconds to kill, and disk check at 6 seconds including kill allowance;
those execute even on work-deadline expiration.
`TimeoutStopSec=600` reserves 180 seconds after the work deadline, exceeding these
88 seconds of final I/O allowances and leaving over 60 seconds for result persistence
and scheduling margin. Child-process clearance remains mandatory before cleanup;
if it cannot be proven, cleanup is refused and the failure is preserved.

`build.result` and `recovery.result` are independent; controller success alone is not
an overall PASS. Logs and manifests persist under root-owned state; dist is validated
and then removed with its precise scratch directory. No output is published.
Recovery failure requires operator diagnosis. Retry the exact run only using
`sudo /bin/bash .../recover.sh <exact-state-path>` after checking the recorded failure;
this never edits limits and never starts services absent from original-active.

An independent external SSH probe is mandatory for a real run. The controller cannot
prove off-host SSH responsiveness. The main coordinating agent must keep a SECOND
SSH connection running, record UTC timestamps, run id, ssh round-trip milliseconds,
exit status and probe failure before/during/after the run. Read-only probe interface:

```sh
date -u +%FT%TZ
systemctl show print-frontend-safe-controller.service -p ActiveState -p SubState
cat /proc/meminfo | sed -n '/^MemAvailable:/p;/^SwapFree:/p'
```

If SSH fails or stops responding, use the independent probe to request controller
stop when possible, preserve the failure evidence, and report failure even if build
later exits zero. Store external probe evidence with the run's review report; do not
claim PASS until it is present. The SSH helper's command/round-trip log is external
evidence; controller's own log is not a substitute.

Overall PASS requires build exit 0, complete controller/build/recovery logs,
dist/index.html manifest/hash, no OOM counters, unchanged limits, external SSH probe
success, original-active services and health restored, scratch removed, and at least
10 GiB free before/after. Neither static checks nor simulated tests meet this gate.

Static checks (only these are authorized in this delivery):

```sh
for f in common.sh launch.sh controller.sh build-child.sh recover.sh self-test.sh; do bash -n "$f" || exit; done
# shellcheck -x common.sh launch.sh controller.sh build-child.sh recover.sh self-test.sh
node --check config-check.mjs
sha256sum common.sh launch.sh controller.sh build-child.sh recover.sh self-test.sh config-check.mjs README.md
```
