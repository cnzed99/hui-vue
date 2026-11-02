# M8 isolated HTTP integration probe

Run only on the development cloud host. This starts a separate candidate Java process on
`127.0.0.1:49180`, a separate ephemeral Redis process on `127.0.0.1:16389`, and a randomly
named disposable MySQL database/user. It never replaces the four runtime services.

The probe clones the new system's development database, applies the reviewed candidate M8
migration only to the clone, creates fresh owner sessions and fresh file/quote metadata
fixtures, and calls the actual order, simulated-payment, print-task, cancellation and
read controllers over HTTP. The file/quote fixture preserves the existing valid quotation
math; upload/rendering are outside this test's scope.

`run.sh` sources the existing runtime environment without printing it. Application secrets,
random database/Redis passwords and bearer tokens stay in memory or mode-0600 scratch
configuration. Only assertions, selected state facts and version hashes are saved.

```sh
bash ops/testing/m8-cancellation-integration/run.sh \
  --jar /absolute/path/to/yudao-server.jar \
  --expected-head <exact-backend-commit> \
  --expected-jar-sha256 <exact-jar-sha256> \
  --output /home/ubuntu/print-system/evidence/testing/<unique-run>
```

The default `full` scope covers automatic cancellations, refund journals, manual review,
idempotency, owner isolation, injected mid-write rollback, six controlled races, owner/admin
snapshot reads, and a restart with cancellation POST disabled. `--scope snapshot` executes
only the additional owner/admin snapshot assertions.

Race synchronization is an isolated-database test trigger waiting for a named MySQL lock.
The probe observes the candidate connection in `information_schema.PROCESSLIST` state
`User lock`, starts the competing HTTP request, verifies both requests remain incomplete,
then releases the gate. This proves both requests overlap while the first transaction
already owns the relevant business row locks. It does not change global MySQL instrumentation.

Cleanup stops both isolated processes, drops only the generated database/user, removes only
the exact scratch directory, and checks the 10 GiB free-space threshold. The result records
cleanup separately from business assertions. The four runtime services remain active.

This probe proves candidate behavior against real Java/MySQL transactions in isolation.
It does not authorize runtime migration or release. Real payment/refund, physical printing,
WeChat device testing and user acceptance remain `NOT_RUN`.
