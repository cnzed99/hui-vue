package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoImageException.Reason.*;

@Component
public class MiniappDemoImageProcessRenderer {
    private static final Pattern OK = Pattern.compile("^OK:(JPEG|PNG):[1-9][0-9]*x[1-9][0-9]*:[0-9a-f]{64}$");
    private final MiniappDemoImageProperties properties;
    private final MiniappDemoFileProperties fileProperties;
    private Semaphore slots;
    private Path runtimeJar;
    private Path scratch;

    public MiniappDemoImageProcessRenderer(MiniappDemoImageProperties properties, MiniappDemoFileProperties fileProperties) {
        this.properties = properties; this.fileProperties = fileProperties;
    }

    @PostConstruct
    public void initialize() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        if (!fileProperties.isEnabled() || properties.getMaxInputBytes() > fileProperties.getMaxBytes()
                || properties.getMaxPdfBytes() > fileProperties.getMaxBytes()) {
            throw new IOException("Image renderer limits must fit the miniapp demo file limits");
        }
        runtimeJar = fileProperties.getRuntimeJar().toRealPath();
        if (!Files.isRegularFile(runtimeJar) || !runtimeJar.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Image renderer requires an immutable executable jar");
        }
        scratch = privateDirectory(fileProperties.getStorageRoot().normalize().resolve("image-scratch"));
        cleanupAbandoned();
        slots = new Semaphore(properties.getMaxConcurrentRenders(), true);
    }

    public byte[] render(InputStream source, long declaredSize) {
        requireEnabled();
        if (source == null || declaredSize < 1 || declaredSize > properties.getMaxInputBytes()) throw failure(INPUT_TOO_LARGE);
        if (!slots.tryAcquire()) throw failure(RENDER_BUSY);
        Path task = null;
        Process process = null;
        Collector collector = null;
        Thread collectorThread = null;
        try {
            task = Files.createTempDirectory(scratch, "task-");
            Files.setPosixFilePermissions(task, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Path input = task.resolve("input.bin");
            Path output = task.resolve("output.pdf");
            copyBounded(source, input, properties.getMaxInputBytes());
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xms32m", "-Xmx384m",
                    "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC", "-Djava.io.tmpdir=" + task,
                    "-Dloader.main=" + MiniappDemoImageRendererMain.class.getName(), "-cp", runtimeJar.toString(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher", input.toString(), output.toString(),
                    Long.toString(properties.getMaxPixels()), Integer.toString(properties.getMaxDimension()),
                    Integer.toString(properties.getMaxPdfBytes()), properties.getRenderVersion());
            builder.redirectErrorStream(true);
            process = builder.start();
            collector = new Collector(process.getInputStream(), 2048);
            collectorThread = new Thread(collector, "miniapp-image-output");
            collectorThread.setDaemon(true); collectorThread.start();
            if (!process.waitFor(properties.getRenderTimeoutSeconds(), TimeUnit.SECONDS)) {
                terminate(process); throw failure(RENDER_TIMEOUT);
            }
            join(collectorThread, process.getInputStream());
            if (collector.failed || collector.overflowed) throw failure(RENDER_UNAVAILABLE);
            String result = collector.result().strip();
            if (process.exitValue() == 0 && OK.matcher(result).matches()
                    && Files.isRegularFile(output) && !Files.isSymbolicLink(output)) {
                byte[] pdf = Files.readAllBytes(output);
                if (pdf.length < 1 || pdf.length > properties.getMaxPdfBytes()) throw failure(PDF_TOO_LARGE);
                return pdf;
            }
            throw switch (process.exitValue()) {
                case 20 -> failure(INVALID_INPUT);
                case 21 -> failure(UNSUPPORTED_FORMAT);
                case 22 -> failure(PIXEL_LIMIT);
                case 23 -> failure(PDF_TOO_LARGE);
                default -> failure(RENDER_UNAVAILABLE);
            };
        } catch (MiniappDemoImageException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(RENDER_TIMEOUT);
        } catch (IOException ignored) {
            throw failure(RENDER_UNAVAILABLE);
        } finally {
            if (process != null && process.isAlive()) terminateUninterruptibly(process);
            joinUninterruptibly(collectorThread, process == null ? null : process.getInputStream());
            if (task != null) deleteTaskDirectory(task, scratch);
            slots.release();
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || slots == null || runtimeJar == null || scratch == null) throw failure(DISABLED);
    }
    private static MiniappDemoImageException failure(MiniappDemoImageException.Reason reason) {
        return new MiniappDemoImageException(reason);
    }
    private static void copyBounded(InputStream source, Path target, long maximum) throws IOException {
        long count = 0;
        try (source; var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (read == 0) continue;
                if (count > maximum - read) throw failure(INPUT_TOO_LARGE);
                count += read; output.write(buffer, 0, read);
            }
        }
        if (count == 0) throw failure(INVALID_INPUT);
    }
    private static Path privateDirectory(Path path) throws IOException {
        if (!Files.exists(path)) Files.createDirectories(path);
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) throw new IOException("Unsafe image scratch directory");
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
        while (process.isAlive()) try { process.waitFor(); } catch (InterruptedException ex) { interrupted = true; }
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
            try { thread.join(100); } catch (InterruptedException ex) { interrupted = true; }
        }
        if (thread.isAlive()) try { if (input != null) input.close(); } catch (IOException ignored) { }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void deleteTaskDirectory(Path task, Path root) {
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
            } catch (IOException ex) { failed = true; }
        }
        private String result() { return output.toString(StandardCharsets.US_ASCII); }
    }
}
