package cn.iocoder.yudao.server.print.miniappdemo.file;

import cn.iocoder.yudao.server.print.file.PdfInspection;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException.Reason.*;

@Service
public class MiniappDemoFileService {
    private static final Logger LOG = LoggerFactory.getLogger(MiniappDemoFileService.class);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private final MiniappDemoFileProperties properties;
    private final MiniappDemoFileRepository repository;
    private final MiniappDemoSessionRepository sessions;
    private final MiniappDemoPdfProcessInspector inspector;
    private final TransactionTemplate transaction;
    private final Object uploadMutex = new Object();
    private Semaphore uploadSlots;
    private Path root;
    private Path objects;
    private Path incoming;
    private Path scratch;
    private ScheduledExecutorService worker;
    private java.nio.channels.FileChannel lockChannel;
    private java.nio.channels.FileLock workerLock;

    public MiniappDemoFileService(MiniappDemoFileProperties properties, MiniappDemoFileRepository repository,
            MiniappDemoSessionRepository sessions, MiniappDemoPdfProcessInspector inspector,
            PlatformTransactionManager manager) {
        this.properties = properties;
        this.repository = repository;
        this.sessions = sessions;
        this.inspector = inspector;
        this.transaction = new TransactionTemplate(manager);
    }

    @PostConstruct
    public void start() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        uploadSlots = new Semaphore(properties.getMaxConcurrentUploads(), true);
        root = privateDirectory(properties.getStorageRoot().normalize());
        objects = privateDirectory(root.resolve("objects"));
        incoming = privateDirectory(root.resolve("incoming"));
        scratch = privateDirectory(root.resolve("parser-scratch"));
        lockChannel = java.nio.channels.FileChannel.open(root.resolve(".worker.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        workerLock = lockChannel.tryLock();
        if (workerLock == null) {
            lockChannel.close();
            throw new IllegalStateException("Only one miniapp demo file worker may use this storage root");
        }
        try {
            cleanupAbandoned();
            repository.recoverInterrupted(System.currentTimeMillis());
        } catch (IOException | RuntimeException failure) {
            closeLock();
            throw failure;
        }
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "miniapp-demo-pdf-worker");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::maintenance, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (worker != null) {
            worker.shutdownNow();
            boolean terminated = false;
            try { terminated = worker.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            if (!terminated) {
                LOG.warn("miniapp_demo_file_worker_shutdown_pending");
                return; // Keep the OS lock until this process exits.
            }
        }
        closeLock();
    }

    public MiniappDemoFileMetadata upload(MiniappDemoIdentity identity, MultipartFile file, String idempotencyKey) {
        requireEnabled();
        if (identity == null || file == null || file.isEmpty() || idempotencyKey == null
                || !idempotencyKey.matches("[!-~]{1,128}")) throw new MiniappDemoFileException(INVALID_INPUT);
        if (file.getSize() > properties.getMaxBytes()) throw new MiniappDemoFileException(TOO_LARGE);
        if (!uploadSlots.tryAcquire()) throw new MiniappDemoFileException(QUEUE_FULL);
        try {
            synchronized (uploadMutex) {
                return uploadSerialized(identity, file, idempotencyKey);
            }
        } finally {
            uploadSlots.release();
        }
    }

    private MiniappDemoFileMetadata uploadSerialized(MiniappDemoIdentity identity, MultipartFile file, String idempotencyKey) {
        String keyDigest = digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        Path temporary = null;
        Path finalPath = null;
        boolean inserted = false;
        try {
            requireActive(identity);
            var prior = repository.findIdempotent(identity, keyDigest);
            if (prior.isPresent()) {
                Content content = copyAndDigest(file, null);
                MiniappDemoFileRow old = prior.get();
                if (!old.contentSha256().equals(content.sha256()) || old.sizeBytes() != content.size()) {
                    throw new MiniappDemoFileException(IDEMPOTENCY_CONFLICT);
                }
                requireActive(identity);
                return metadata(expireIfNeeded(owned(old.id(), identity)));
            }
            long now = System.currentTimeMillis();
            if (repository.pendingCount() >= properties.getQueueCapacity()) throw new MiniappDemoFileException(QUEUE_FULL);
            if (repository.activeCount(identity, now) >= properties.getMaxActiveFilesPerPrincipal()) {
                throw new MiniappDemoFileException(PRINCIPAL_LIMIT);
            }
            long available = Files.getFileStore(root).getUsableSpace();
            if (available < properties.getMinFreeBytes() || file.getSize() > available - properties.getMinFreeBytes()) {
                throw new MiniappDemoFileException(CAPACITY_LOW);
            }
            temporary = Files.createTempFile(incoming, "upload-", ".part");
            Content content = copyAndDigest(file, temporary);
            String id = UUID.randomUUID().toString();
            finalPath = objectPath(id);
            Files.move(temporary, finalPath);
            temporary = null;
            MiniappDemoFileRow row = transaction.execute(status -> {
                var locked = sessions.lockIdentity(identity).orElseThrow(MiniappDemoFileService::sessionInvalid);
                long committedAt = System.currentTimeMillis();
                if (!locked.activeAt(committedAt)) throw sessionInvalid();
                if (repository.pendingCount() >= properties.getQueueCapacity()) throw new MiniappDemoFileException(QUEUE_FULL);
                if (repository.activeCount(identity, committedAt) >= properties.getMaxActiveFilesPerPrincipal()) {
                    throw new MiniappDemoFileException(PRINCIPAL_LIMIT);
                }
                long expiresAt = Math.min(Math.addExact(committedAt, properties.getRetentionSeconds() * 1000), locked.expiresAtMs());
                if (expiresAt <= committedAt) throw sessionInvalid();
                MiniappDemoFileRow committed = new MiniappDemoFileRow(id, identity.tenantId(), identity.storeId(), identity.principalId(),
                        keyDigest, content.sha256(), displayName(file.getOriginalFilename()), content.size(), null,
                        "UPLOADED", null, id, committedAt, committedAt, expiresAt, 0);
                repository.insert(committed);
                return committed;
            });
            inserted = true;
            return metadata(row);
        } catch (DuplicateKeyException ex) {
            removeTemporary(finalPath);
            finalPath = null;
            MiniappDemoFileRow existing = repository.findIdempotent(identity, keyDigest)
                    .orElseThrow(() -> new MiniappDemoFileException(STORAGE_UNAVAILABLE));
            try {
                Content content = copyAndDigest(file, null);
                if (!existing.contentSha256().equals(content.sha256()) || existing.sizeBytes() != content.size()) {
                    throw new MiniappDemoFileException(IDEMPOTENCY_CONFLICT);
                }
                requireActive(identity);
                return metadata(existing);
            } catch (IOException failure) {
                throw new MiniappDemoFileException(STORAGE_UNAVAILABLE);
            }
        } catch (IOException | DataAccessException ex) {
            throw new MiniappDemoFileException(STORAGE_UNAVAILABLE);
        } finally {
            removeTemporary(temporary);
            if (!inserted) removeTemporary(finalPath);
        }
    }

    public MiniappDemoFileMetadata get(MiniappDemoIdentity principal, String id) {
        requireEnabled();
        requireActive(principal);
        return metadata(expireIfNeeded(owned(id, principal)));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new MiniappDemoFileException(DISABLED);
    }
    private MiniappDemoFileRow owned(String id, MiniappDemoIdentity principal) {
        if (!canonicalId(id)) throw new MiniappDemoFileException(NOT_FOUND);
        return repository.findOwned(id, principal).orElseThrow(() -> new MiniappDemoFileException(NOT_FOUND));
    }
    private void requireActive(MiniappDemoIdentity identity) {
        transaction.executeWithoutResult(status -> {
            var locked = sessions.lockIdentity(identity).orElseThrow(MiniappDemoFileService::sessionInvalid);
            if (!locked.activeAt(System.currentTimeMillis())) throw sessionInvalid();
        });
    }
    private static MiniappDemoSessionException sessionInvalid() {
        return new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);
    }
    private MiniappDemoFileRow expireIfNeeded(MiniappDemoFileRow row) {
        if (row.expiresAtMs() <= System.currentTimeMillis() && !"DELETED".equals(row.status())) {
            repository.deleted(row, "EXPIRED", System.currentTimeMillis());
            row = repository.findExact(row).orElseThrow(() -> new MiniappDemoFileException(NOT_FOUND));
            purge(row);
        }
        return row;
    }
    private MiniappDemoFileMetadata metadata(MiniappDemoFileRow row) {
        return new MiniappDemoFileMetadata(row.id(), row.displayName(), row.sizeBytes(), row.pageCount(), row.status(),
                row.failureCode(), row.createdAtMs(), row.expiresAtMs(),
                "READY".equals(row.status()) && row.expiresAtMs() > System.currentTimeMillis());
    }

    private void maintenance() {
        try {
            repository.recoverInterrupted(System.currentTimeMillis());
            for (MiniappDemoFileRow row : repository.cleanupCandidates(System.currentTimeMillis(), 16)) {
                row = expireIfNeeded(row);
                purge(row);
            }
            var next = repository.claimNext(System.currentTimeMillis());
            if (next.isEmpty()) return;
            MiniappDemoFileRow row = next.get();
            if (row.processingAttempts() > properties.getMaxProcessingAttempts()) {
                repository.failed(row, "PROCESSING_RETRY_LIMIT", System.currentTimeMillis());
            } else {
                try {
                    Path path = objectPath(row.storageKey());
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                        repository.failed(row, "FILE_MISSING", System.currentTimeMillis());
                    } else {
                        int pages = inspector.inspect(path, scratch, properties.getMaxBytes(), properties.getMaxPages(),
                                properties.getParserTimeoutSeconds());
                        repository.ready(row, pages, System.currentTimeMillis());
                    }
                } catch (PdfInspection.Failure failure) {
                    repository.failed(row, failure.code(), System.currentTimeMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    repository.failed(row, "PDF_INTERRUPTED", System.currentTimeMillis());
                }
            }
            MiniappDemoFileRow current = repository.findExact(row).orElse(row);
            purge(expireIfNeeded(current));
        } catch (Exception failure) {
            LOG.warn("miniapp_demo_file_worker_retry errorType={}", failure.getClass().getSimpleName());
        }
    }

    private void purge(MiniappDemoFileRow row) {
        if (!"FAILED".equals(row.status()) && !"DELETED".equals(row.status())) return;
        try {
            Files.deleteIfExists(objectPath(row.storageKey()));
            repository.purged(row, System.currentTimeMillis());
        } catch (IOException ex) {
            LOG.warn("miniapp_demo_file_blob_purge_pending");
        }
    }
    private void cleanupAbandoned() throws IOException {
        for (Path directory : new Path[]{incoming, scratch}) {
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path path : files) {
                    String name = path.getFileName().toString();
                    if (directory.equals(scratch) && name.startsWith("task-")
                            && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                        MiniappDemoPdfProcessInspector.deleteTaskDirectory(path, scratch);
                        continue;
                    }
                    boolean owned = directory.equals(incoming) ? name.startsWith("upload-") && name.endsWith(".part")
                            : name.startsWith("PDFBox") && name.endsWith(".tmp");
                    if (owned && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
                }
            }
        }
        try (var files = Files.newDirectoryStream(objects, "*.pdf")) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                String key = name.substring(0, name.length() - 4);
                if (canonicalId(key) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !repository.storageReferenced(key)) Files.delete(path);
            }
        }
    }
    private static Path privateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path);
            Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY);
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe storage directory");
        if (!Files.getPosixFilePermissions(path).equals(PRIVATE_DIRECTORY)) throw new IOException("Storage permissions must be owner-only");
        return path.toRealPath();
    }
    private void closeLock() {
        try {
            if (workerLock != null && workerLock.isValid()) workerLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ex) {
            LOG.warn("miniapp_demo_file_worker_lock_release_pending");
        }
    }
    private Content copyAndDigest(MultipartFile file, Path target) throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        try (InputStream input = file.getInputStream(); OutputStream output = target == null ? OutputStream.nullOutputStream()
                : Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (n == 0) continue;
                if (count > properties.getMaxBytes() - n) throw new MiniappDemoFileException(TOO_LARGE);
                count += n;
                output.write(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        }
        if (count == 0) throw new MiniappDemoFileException(INVALID_INPUT);
        if (target != null) {
            try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
        }
        return new Content(count, HexFormat.of().formatHex(digest.digest()));
    }
    private Path objectPath(String key) {
        if (!canonicalId(key)) throw new MiniappDemoFileException(STORAGE_UNAVAILABLE);
        Path path = objects.resolve(key + ".pdf").normalize();
        if (!path.getParent().equals(objects)) throw new MiniappDemoFileException(STORAGE_UNAVAILABLE);
        return path;
    }
    private static String displayName(String name) {
        if (name == null || name.isBlank()) return "document.pdf";
        String value = Normalizer.normalize(name, Normalizer.Form.NFKC).replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(200).forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT || codePoint == '/' || codePoint == '\\' || codePoint == '"') result.append('_');
            else result.appendCodePoint(codePoint);
        });
        String cleaned = result.toString().strip();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "document.pdf" : cleaned;
    }
    private static boolean canonicalId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String digest(byte[] value) { return HexFormat.of().formatHex(sha256().digest(value)); }
    private void removeTemporary(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { LOG.warn("miniapp_demo_file_temporary_cleanup_pending"); }
    }
    private record Content(long size, String sha256) {}
}
