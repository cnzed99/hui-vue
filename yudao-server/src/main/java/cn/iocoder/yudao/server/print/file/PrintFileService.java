package cn.iocoder.yudao.server.print.file;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.server.print.file.PrintFileException.Reason.*;

@Service
public class PrintFileService {
    private static final Logger LOG = LoggerFactory.getLogger(PrintFileService.class);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private final PrintFileProperties properties;
    private final PrintFileRepository repository;
    private final PrintScopeService scopes;
    private Path root;
    private Path objects;
    private Path incoming;
    private Path scratch;
    private ScheduledExecutorService worker;
    private java.nio.channels.FileChannel lockChannel;
    private java.nio.channels.FileLock workerLock;

    public PrintFileService(PrintFileProperties properties, PrintFileRepository repository, PrintScopeService scopes) {
        this.properties = properties; this.repository = repository; this.scopes = scopes;
    }
    @PostConstruct
    public void start() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        root = privateDirectory(properties.getStorageRoot().normalize());
        objects = privateDirectory(root.resolve("objects"));
        incoming = privateDirectory(root.resolve("incoming"));
        scratch = privateDirectory(root.resolve("parser-scratch"));
        Path lockPath = root.resolve(".worker.lock");
        lockChannel = java.nio.channels.FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        workerLock = lockChannel.tryLock();
        if (workerLock == null) {
            lockChannel.close();
            throw new IllegalStateException("Only one print file worker may use this storage root");
        }
        try {
            cleanupAbandoned();
            repository.recoverInterrupted(System.currentTimeMillis());
        } catch (IOException | RuntimeException failure) {
            closeLock();
            throw failure;
        }
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "print-pdf-worker");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::maintenance, 1, 1, TimeUnit.SECONDS);
    }
    @PreDestroy
    public void stop() {
        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(3, TimeUnit.SECONDS)) return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        closeLock();
    }
    private void closeLock() {
        try {
            if (workerLock != null && workerLock.isValid()) workerLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ex) {
            LOG.warn("print_file_worker_lock_release_pending");
        }
    }
    private void cleanupAbandoned() throws IOException {
        for (Path directory : new Path[]{incoming, scratch}) {
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path path : files) {
                    String name = path.getFileName().toString();
                    boolean ownTemporary = directory.equals(incoming)
                            ? name.startsWith("upload-") && name.endsWith(".part")
                            : name.startsWith("PDFBox") && name.endsWith(".tmp");
                    if (ownTemporary && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
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
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Private storage directory is not safe");
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        if (!permissions.equals(PRIVATE_DIRECTORY)) {
            throw new IOException("Private storage directory permissions are not owner-only");
        }
        return path.toRealPath();
    }
    private PrintScope scope() {
        PrintScope scope = scopes.requireCurrent();
        if (!properties.isEnabled()) throw new PrintFileException(DISABLED);
        return scope;
    }

    /** Serializes acceptance in the single-server deployment to bound the durable queue. */
    public synchronized PrintFileMetadata upload(MultipartFile file, String idempotencyKey) {
        PrintScope scope = scope();
        if (file == null || file.isEmpty() || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128 || idempotencyKey.chars().anyMatch(Character::isISOControl)) {
            throw new PrintFileException(INVALID_INPUT);
        }
        if (file.getSize() > properties.getMaxBytes()) throw new PrintFileException(TOO_LARGE);
        String keyDigest = digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        Path temporary = null;
        Path finalPath = null;
        boolean inserted = false;
        try {
            var prior = repository.findIdempotent(scope, keyDigest);
            if (prior.isPresent()) {
                Content content = copyAndDigest(file, null);
                PrintFileRow old = prior.get();
                if (!old.contentSha256().equals(content.sha256()) || old.sizeBytes() != content.size()) {
                    throw new PrintFileException(IDEMPOTENCY_CONFLICT);
                }
                return metadata(expireIfNeeded(owned(old.id(), scope)));
            }
            if (repository.pendingCount() >= properties.getQueueCapacity()) throw new PrintFileException(QUEUE_FULL);
            long available = Files.getFileStore(root).getUsableSpace();
            if (available < properties.getMinFreeBytes()
                    || file.getSize() > available - properties.getMinFreeBytes()) throw new PrintFileException(CAPACITY_LOW);
            temporary = Files.createTempFile(incoming, "upload-", ".part");
            Content content = copyAndDigest(file, temporary);
            String id = UUID.randomUUID().toString();
            finalPath = objectPath(id);
            Files.move(temporary, finalPath); // No overwrite; same private filesystem.
            temporary = null;
            long now = System.currentTimeMillis();
            PrintFileRow row = new PrintFileRow(id, scope.tenantId(), scope.storeId(), scope.userId(),
                    keyDigest, content.sha256(), displayName(file.getOriginalFilename()), content.size(), null,
                    "UPLOADED", null, id, now, now, Math.addExact(now, properties.getRetentionSeconds() * 1000), 0);
            try {
                repository.insert(row);
                inserted = true;
                return metadata(row);
            } catch (DuplicateKeyException ex) {
                // Defensive cross-request race handling; only the newly created UUID blob is removed.
                Files.deleteIfExists(finalPath);
                finalPath = null;
                PrintFileRow existing = repository.findIdempotent(scope, keyDigest)
                        .orElseThrow(() -> new PrintFileException(STORAGE_UNAVAILABLE));
                if (!existing.contentSha256().equals(content.sha256()) || existing.sizeBytes() != content.size()) {
                    throw new PrintFileException(IDEMPOTENCY_CONFLICT);
                }
                return metadata(existing);
            }
        } catch (IOException | DataAccessException ex) {
            throw new PrintFileException(STORAGE_UNAVAILABLE);
        } finally {
            removeTemporary(temporary);
            if (!inserted) removeTemporary(finalPath);
        }
    }

    public synchronized PrintFileMetadata get(String id) {
        PrintScope scope = scope();
        return metadata(expireIfNeeded(owned(id, scope)));
    }

    public synchronized OpenContent open(String id) {
        PrintScope scope = scope();
        PrintFileRow row = expireIfNeeded(owned(id, scope));
        if ("DELETED".equals(row.status())) throw new PrintFileException(NOT_FOUND);
        if (!"READY".equals(row.status())) throw new PrintFileException(NOT_READY);
        try {
            Path path = objectPath(row.storageKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new PrintFileException(NOT_FOUND);
            }
            return new OpenContent(metadata(row), Files.newInputStream(path, StandardOpenOption.READ));
        } catch (IOException ex) {
            throw new PrintFileException(STORAGE_UNAVAILABLE);
        }
    }

    public synchronized PrintFileMetadata delete(String id) {
        PrintScope scope = scope();
        PrintFileRow row = owned(id, scope);
        repository.deleted(row, null, System.currentTimeMillis());
        purge(owned(id, scope));
        return metadata(owned(id, scope));
    }

    private PrintFileRow owned(String id, PrintScope scope) {
        if (!canonicalId(id)) throw new PrintFileException(NOT_FOUND);
        // A different owner/store/tenant is indistinguishable from a nonexistent identifier.
        return repository.findOwned(id, scope).orElseThrow(() -> new PrintFileException(NOT_FOUND));
    }
    private PrintFileRow expireIfNeeded(PrintFileRow row) {
        if (row.expiresAtMs() <= System.currentTimeMillis() && !"DELETED".equals(row.status())) {
            repository.deleted(row, "EXPIRED", System.currentTimeMillis());
            row = owned(row.id(), PrintFileRepository.scope(row));
            purge(row);
        }
        return row;
    }
    private PrintFileMetadata metadata(PrintFileRow row) {
        return new PrintFileMetadata(row.id(), row.displayName(), "application/pdf", row.sizeBytes(),
                row.pageCount(), row.status(), row.failureCode(), Instant.ofEpochMilli(row.createdAtMs()),
                Instant.ofEpochMilli(row.expiresAtMs()),
                "READY".equals(row.status()) && row.expiresAtMs() > System.currentTimeMillis());
    }

    private void maintenance() {
        try {
            // A process-local exclusive file lock plus single executor makes recovery safe here.
            // A leftover PROCESSING row can only be from a completed/failed previous tick or restart.
            repository.recoverInterrupted(System.currentTimeMillis());
            for (PrintFileRow row : repository.cleanupCandidates(System.currentTimeMillis(), 16)) {
                row = expireIfNeeded(row);
                purge(row);
            }
            var next = repository.claimNext(System.currentTimeMillis());
            if (next.isEmpty()) return;
            PrintFileRow row = next.get();
            if (row.processingAttempts() > properties.getMaxProcessingAttempts()) {
                repository.failed(row, "PROCESSING_RETRY_LIMIT", System.currentTimeMillis());
            } else {
                try {
                    Path path = objectPath(row.storageKey());
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                        repository.failed(row, "FILE_MISSING", System.currentTimeMillis());
                    } else {
                        int pages = PdfInspection.inspect(path, scratch, properties.getMaxBytes(), properties.getMaxPages());
                        repository.ready(row, pages, System.currentTimeMillis());
                    }
                } catch (PdfInspection.Failure failure) {
                    repository.failed(row, failure.code(), System.currentTimeMillis());
                }
            }
            PrintFileRow current = owned(row.id(), PrintFileRepository.scope(row));
            purge(expireIfNeeded(current));
        } catch (Exception failure) {
            // No parser text, filenames, credentials or SQL input values in logs.
            LOG.warn("print_file_worker_retry errorType={}", failure.getClass().getSimpleName());
        }
    }
    private void purge(PrintFileRow row) {
        if (!"FAILED".equals(row.status()) && !"DELETED".equals(row.status())) return;
        try {
            Files.deleteIfExists(objectPath(row.storageKey()));
            repository.purged(row, System.currentTimeMillis());
        } catch (IOException ex) {
            LOG.warn("print_file_private_blob_purge_pending");
        }
    }
    private Content copyAndDigest(MultipartFile file, Path target) throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        int chunks = 0;
        try (InputStream input = file.getInputStream();
             OutputStream output = target == null ? OutputStream.nullOutputStream()
                     : Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (n == 0) continue;
                if (count > properties.getMaxBytes() - n) throw new PrintFileException(TOO_LARGE);
                count += n;
                if (target != null && ++chunks % 64 == 0
                        && Files.getFileStore(root).getUsableSpace() < properties.getMinFreeBytes()) {
                    throw new PrintFileException(CAPACITY_LOW);
                }
                output.write(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        }
        if (count == 0) throw new PrintFileException(INVALID_INPUT);
        if (target != null) {
            try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
        return new Content(count, HexFormat.of().formatHex(digest.digest()));
    }
    private Path objectPath(String key) {
        if (!canonicalId(key)) throw new PrintFileException(STORAGE_UNAVAILABLE);
        Path path = objects.resolve(key + ".pdf").normalize();
        if (!path.getParent().equals(objects)) throw new PrintFileException(STORAGE_UNAVAILABLE);
        return path;
    }
    private static boolean canonicalId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }
    static String displayName(String name) {
        if (name == null || name.isBlank()) return "document.pdf";
        String value = Normalizer.normalize(name, Normalizer.Form.NFKC).replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(200).forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || codePoint == '/' || codePoint == '\\' || codePoint == '"') result.append('_');
            else result.appendCodePoint(codePoint);
        });
        String cleaned = result.toString().strip();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "document.pdf" : cleaned;
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private void removeTemporary(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { LOG.warn("print_file_temporary_cleanup_pending"); }
    }
    public record OpenContent(PrintFileMetadata metadata, InputStream stream) {}
    private record Content(long size, String sha256) {}
}
