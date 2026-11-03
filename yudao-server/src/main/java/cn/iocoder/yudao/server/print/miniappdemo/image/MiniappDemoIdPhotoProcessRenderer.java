package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileVariant;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoException.Reason.*;

@Component
public final class MiniappDemoIdPhotoProcessRenderer {
    private static final Pattern OK = Pattern.compile("(?m)^OK:(ONE_INCH|TWO_INCH|THREE_INCH):(JPEG|PNG):([1-9][0-9]*)x([1-9][0-9]*):([0-9a-f]{64})$");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final MiniappDemoIdPhotoProperties properties;
    private final MiniappDemoFileProperties fileProperties;
    private final MiniappDemoProcessAdmission processAdmission;
    private final MiniappDemoFileService fileService;
    private final Object lifecycleMonitor = new Object();
    private boolean stopping;
    private int activeRenders;
    private Path runtimeJar;
    private Path scratch;

    public MiniappDemoIdPhotoProcessRenderer(MiniappDemoIdPhotoProperties properties,
            MiniappDemoFileProperties fileProperties, MiniappDemoProcessAdmission processAdmission,
            MiniappDemoFileService fileService) {
        this.properties = properties; this.fileProperties = fileProperties;
        this.processAdmission = processAdmission; this.fileService = fileService;
    }

    @PostConstruct
    public void initialize() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        if (!fileProperties.isEnabled() || properties.getMaxInputBytes() > fileProperties.getMaxBytes()
                || properties.getMaxPdfBytes() > fileProperties.getMaxBytes()) {
            throw new IOException("Id photo renderer limits must fit the miniapp demo file limits");
        }
        fileService.requireStorageLockHeld();
        runtimeJar = fileProperties.getRuntimeJar().toRealPath();
        if (!Files.isRegularFile(runtimeJar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(runtimeJar)
                || !runtimeJar.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Id photo renderer requires an immutable executable jar");
        }
        scratch = privateDirectory(fileProperties.getStorageRoot().normalize().resolve("id-photo-scratch"));
        cleanupAbandoned();
    }

    @PreDestroy
    public void stop() {
        boolean interrupted = false;
        boolean drained;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getRenderTimeoutSeconds() + 5L);
        synchronized (lifecycleMonitor) {
            stopping = true;
            while (activeRenders > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining); }
                catch (InterruptedException failure) { interrupted = true; break; }
            }
            drained = activeRenders == 0;
        }
        if (!drained) fileService.retainStorageLockUntilProcessExit();
        if (interrupted) Thread.currentThread().interrupt();
    }

    public byte[] render(MultipartFile image, MiniappDemoFileVariant variant) {
        requireEnabled();
        if (image == null || image.isEmpty() || variant == null || image.getSize() < 1
                || image.getSize() > properties.getMaxInputBytes()) throw failure(INPUT_TOO_LARGE);
        beginRender();
        try { return renderWhileStorageLocked(image, variant); }
        finally { endRender(); }
    }

    private byte[] renderWhileStorageLocked(MultipartFile image, MiniappDemoFileVariant variant) {
        Path task = null;
        Process process = null;
        Collector collector = null;
        Thread collectorThread = null;
        MiniappDemoProcessAdmission.Lease lease = null;
        try {
            task = Files.createTempDirectory(scratch, "task-");
            Files.setPosixFilePermissions(task, PRIVATE_DIRECTORY);
            Path input = task.resolve("input.bin");
            Path output = task.resolve("output.pdf");
            copyBounded(image, input, properties.getMaxInputBytes());
            lease = processAdmission.tryAcquire();
            if (lease == null) throw failure(RENDER_BUSY);
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xms32m", "-Xmx192m",
                    "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
                    "-Djava.io.tmpdir=" + task, "-Dloader.main=" + MiniappDemoIdPhotoRendererMain.class.getName(),
                    "-cp", runtimeJar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher",
                    input.toString(), output.toString(), variant.name(), Long.toString(properties.getMaxInputBytes()),
                    Integer.toString(properties.getMaxWidth()), Integer.toString(properties.getMaxHeight()),
                    Long.toString(properties.getMaxPixels()), Integer.toString(properties.getMaxPdfBytes()),
                    properties.getRenderVersion());
            builder.redirectErrorStream(true);
            process = builder.start();
            collector = new Collector(process.getInputStream(), 2048);
            collectorThread = new Thread(collector, "miniapp-id-photo-output");
            collectorThread.setDaemon(true); collectorThread.start();
            if (!process.waitFor(properties.getRenderTimeoutSeconds(), TimeUnit.SECONDS)) {
                terminate(process); throw failure(RENDER_TIMEOUT);
            }
            join(collectorThread, process.getInputStream());
            if (collector.failed || collector.overflowed) throw failure(RENDER_UNAVAILABLE);
            var success = OK.matcher(collector.result().strip());
            String reportedVariant = null, reportedHash = null;
            boolean exactlyOne = false;
            if (success.find()) {
                reportedVariant = success.group(1); reportedHash = success.group(5); exactlyOne = !success.find();
            }
            if (process.exitValue() == 0 && exactlyOne && variant.name().equals(reportedVariant)
                    && Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output)) {
                byte[] pdf = Files.readAllBytes(output);
                if (pdf.length < 8 || pdf.length > properties.getMaxPdfBytes()) throw failure(PDF_TOO_LARGE);
                if (!pdfHeaderAndTrailer(pdf) || !HexFormat.of().formatHex(hash(pdf)).equals(reportedHash)) {
                    throw failure(RENDER_UNAVAILABLE);
                }
                return pdf;
            }
            throw switch (process.exitValue()) {
                case 20 -> failure(INVALID_INPUT);
                case 21 -> failure(UNSUPPORTED_FORMAT);
                case 22 -> failure(PIXEL_LIMIT);
                case 23 -> failure(PDF_TOO_LARGE);
                default -> failure(RENDER_UNAVAILABLE);
            };
        } catch (MiniappDemoIdPhotoException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw failure(RENDER_TIMEOUT);
        } catch (IOException ignored) {
            throw failure(RENDER_UNAVAILABLE);
        } finally {
            if (process != null && process.isAlive()) terminateUninterruptibly(process);
            joinUninterruptibly(collectorThread, process == null ? null : process.getInputStream());
            if (lease != null) lease.close();
            if (task != null) deleteTaskDirectory(task, scratch);
        }
    }

    private void beginRender() {
        synchronized (lifecycleMonitor) {
            if (stopping) throw failure(RENDER_UNAVAILABLE);
            try { fileService.requireStorageLockHeld(); }
            catch (RuntimeException failure) { throw failure(RENDER_UNAVAILABLE); }
            activeRenders += 1;
        }
    }

    private void endRender() {
        synchronized (lifecycleMonitor) {
            activeRenders -= 1;
            lifecycleMonitor.notifyAll();
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || runtimeJar == null || scratch == null) throw failure(DISABLED);
    }

    private static void copyBounded(MultipartFile image, Path target, long maximum) throws IOException {
        long count = 0;
        try (InputStream input = image.getInputStream(); OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(target, PRIVATE_FILE);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (count > maximum - read) throw failure(INPUT_TOO_LARGE);
                count += read; output.write(buffer, 0, read);
            }
        }
        if (count == 0) throw failure(INVALID_INPUT);
        try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static boolean pdfHeaderAndTrailer(byte[] value) {
        byte[] header = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (!Arrays.equals(header, Arrays.copyOf(value, header.length))) return false;
        int start = Math.max(0, value.length - 1024);
        return new String(value, start, value.length - start, StandardCharsets.ISO_8859_1).contains("%%EOF");
    }

    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    private static MiniappDemoIdPhotoException failure(MiniappDemoIdPhotoException.Reason reason) {
        return new MiniappDemoIdPhotoException(reason);
    }

    private static Path privateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path); Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY);
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path).equals(PRIVATE_DIRECTORY)) {
            throw new IOException("Unsafe id photo scratch directory");
        }
        return path.toRealPath();
    }

    private void cleanupAbandoned() throws IOException {
        try (var paths = Files.newDirectoryStream(scratch, "task-*")) {
            for (Path path : paths) deleteTaskDirectory(path, scratch);
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); }
    }

    private static void terminateUninterruptibly(Process process) {
        boolean interrupted = false; process.destroyForcibly();
        while (process.isAlive()) try { process.waitFor(); } catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void join(Thread thread, InputStream input) throws InterruptedException {
        if (thread == null) return;
        thread.join(2000);
        if (thread.isAlive()) { try { if (input != null) input.close(); } catch (IOException ignored) { } thread.join(1000); }
    }

    private static void joinUninterruptibly(Thread thread, InputStream input) {
        if (thread == null) return;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(100); } catch (InterruptedException failure) { interrupted = true; }
        }
        if (thread.isAlive()) try { if (input != null) input.close(); } catch (IOException ignored) { }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static void deleteTaskDirectory(Path task, Path root) {
        try {
            Path realRoot = root.toRealPath();
            Path candidate = task.toAbsolutePath().normalize();
            if (!candidate.getParent().equals(realRoot) || !candidate.getFileName().toString().startsWith("task-")
                    || Files.isSymbolicLink(candidate)) return;
            Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure; Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) { } catch (IOException ignored) { }
    }

    private static final class Collector implements Runnable {
        private final InputStream input; private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean overflowed; private volatile boolean failed;
        private Collector(InputStream input, int limit) { this.input = input; this.limit = limit; }
        @Override public void run() {
            byte[] buffer = new byte[256];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int remaining = limit - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(remaining, count));
                    if (count > remaining) overflowed = true;
                }
            } catch (IOException failure) { failed = true; }
        }
        private String result() { return output.toString(StandardCharsets.US_ASCII); }
    }
}
