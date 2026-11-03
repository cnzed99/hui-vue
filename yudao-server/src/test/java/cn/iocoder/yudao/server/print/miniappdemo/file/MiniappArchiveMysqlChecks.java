package cn.iocoder.yudao.server.print.miniappdemo.file;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run against an explicitly isolated MySQL instance provisioned by the candidate probe. */
public final class MiniappArchiveMysqlChecks {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final MultipartFile PDF = new FixturePdf();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        if (port < 40000 || port > 59999) throw new IllegalArgumentException("Expected isolated loopback port");
        Path storage = Path.of(args[1]).toRealPath();
        if (!storage.getFileName().toString().startsWith("print-zip-db-")) {
            throw new IllegalArgumentException("Expected isolated storage root");
        }
        var dataSource = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/zip_candidate?connectTimeout=3000&socketTimeout=5000&sslMode=DISABLED", "root", "");
        var jdbc = new JdbcTemplate(dataSource);
        var sessions = new MiniappDemoSessionRepository(jdbc);
        var tx = new DataSourceTransactionManager(dataSource);
        var admission = new MiniappDemoProcessAdmission();
        Path left = Files.createDirectory(storage.resolve("left"));
        Path right = Files.createDirectory(storage.resolve("right"));
        MiniappDemoIdentity first = identity(sessions, SHA_A);
        MiniappDemoIdentity second = identity(sessions, SHA_B);
        CyclicBarrier initiallyAbsent = new CyclicBarrier(2);
        var repo1 = new RacingRepository(jdbc, initiallyAbsent);
        var repo2 = new RacingRepository(jdbc, initiallyAbsent);
        var pool = Executors.newFixedThreadPool(2);
        String persisted;
        try (var blocked = admission.tryAcquire()) {
            var a = files(left, repo1, sessions, admission, tx);
            var b = files(right, repo2, sessions, admission, tx);
            a.start();
            try {
                b.start();
                try {
                    var leftResult = pool.submit(() -> upload(a, first, "same-key", SHA_A, 0));
                    var rightResult = pool.submit(() -> upload(b, first, "same-key", SHA_A, 0));
                    persisted = leftResult.get(10, TimeUnit.SECONDS).id();
                    check(persisted.equals(rightResult.get(10, TimeUnit.SECONDS).id()), "forced duplicate converges");
                    check(repo1.recoveryRead.get() || repo2.recoveryRead.get(), "duplicate lookup executed");
                    check(persisted.equals(upload(a, first, "same-key", SHA_A, 0).id()), "ordinary retry converges");
                    conflict(() -> upload(a, first, "same-key", SHA_B, 0));
                    conflict(() -> upload(a, first, "same-key", SHA_A, 1));
                    conflict(() -> a.upload(first, PDF, "same-key"));
                    check(!persisted.equals(upload(a, second, "same-key", SHA_A, 0).id()), "principal key isolation");
                    try {
                        a.get(second, persisted);
                        throw new AssertionError("foreign file exposed");
                    } catch (MiniappDemoFileException expected) {
                        check(expected.status().value() == 404, "foreign read rejected");
                    }
                    rejectBrokenConstraint(jdbc, persisted);
                } finally {
                    b.stop();
                }
            } finally {
                a.stop();
                pool.shutdownNow();
            }
            jdbc.update("UPDATE print_miniapp_demo_file SET status='PROCESSING' WHERE id=?", persisted);
            Path winner = Files.exists(left.resolve("objects").resolve(persisted + ".pdf")) ? left : right;
            var restarted = files(winner, new MiniappDemoFileRepository(jdbc), sessions, admission, tx);
            restarted.start();
            try {
                var restored = upload(restarted, first, "same-key", SHA_A, 0);
                check(persisted.equals(restored.id()) && "UPLOADED".equals(restored.status()), "restart restores binding");
                jdbc.update("UPDATE print_miniapp_demo_file SET status='FAILED' WHERE id=?", persisted);
                check("FAILED".equals(upload(restarted, first, "same-key", SHA_A, 0).status()), "failed binding retained");
            } finally {
                restarted.stop();
            }
        }
        System.out.println("ZIP_MYSQL_DDL_IDEMPOTENCY_PASS");
    }

    private static void rejectBrokenConstraint(JdbcTemplate jdbc, String id) {
        try {
            jdbc.update("UPDATE print_miniapp_demo_file SET archive_entry_ordinal=NULL WHERE id=?", id);
            throw new AssertionError("partial source accepted");
        } catch (DataAccessException expected) {
            check(expected.getMostSpecificCause() instanceof java.sql.SQLException sql && sql.getErrorCode() == 3819
                            && sql.getMessage().contains("chk_print_demo_file_archive_source"),
                    "paired-source constraint");
        }
    }

    private static MiniappDemoIdentity identity(MiniappDemoSessionRepository sessions, String token) {
        String principal = UUID.randomUUID().toString();
        String session = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + 120000;
        sessions.insert(principal, session, 1, 1, token, now, expires);
        return new MiniappDemoIdentity(session, principal, 1, 1, expires);
    }

    private static MiniappDemoFileService files(Path root, MiniappDemoFileRepository repo,
            MiniappDemoSessionRepository sessions, MiniappDemoProcessAdmission admission, DataSourceTransactionManager tx) {
        var properties = new MiniappDemoFileProperties();
        properties.setEnabled(true);
        properties.setStorageRoot(root);
        properties.setRuntimeJar(Path.of("/unused-parser-fixture.jar"));
        properties.setMaxBytes(2097152);
        properties.setMaxPages(20);
        properties.setRetentionSeconds(120);
        return new MiniappDemoFileService(properties, repo, sessions, null, admission, tx);
    }

    private static MiniappDemoFileMetadata upload(MiniappDemoFileService files, MiniappDemoIdentity identity,
            String key, String archiveSha, int ordinal) {
        return files.uploadFromArchive(identity, PDF, key, archiveSha, ordinal,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    private static void conflict(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected conflict");
        } catch (MiniappDemoFileException expected) {
            check(expected.status().value() == 409, "binding mismatch returns conflict");
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class RacingRepository extends MiniappDemoFileRepository {
        private final CyclicBarrier barrier;
        private final AtomicBoolean firstRead = new AtomicBoolean(true);
        private final AtomicBoolean recoveryRead = new AtomicBoolean();

        private RacingRepository(JdbcTemplate jdbc, CyclicBarrier barrier) {
            super(jdbc);
            this.barrier = barrier;
        }

        @Override
        Optional<MiniappDemoFileRow> findIdempotent(MiniappDemoIdentity identity, String digest) {
            var row = super.findIdempotent(identity, digest);
            if (firstRead.compareAndSet(true, false)) {
                check(row.isEmpty(), "both uploads observe absence before race");
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            } else if (row.isPresent()) {
                recoveryRead.set(true);
            }
            return row;
        }
    }

    private static final class FixturePdf implements MultipartFile {
        private final byte[] bytes = "%PDF-1.4\nfixture\n".getBytes(StandardCharsets.US_ASCII);
        public String getName() { return "file"; }
        public String getOriginalFilename() { return "fixture.pdf"; }
        public String getContentType() { return "application/pdf"; }
        public boolean isEmpty() { return false; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File destination) { throw new UnsupportedOperationException(); }
    }
}
