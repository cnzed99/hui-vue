package cn.iocoder.yudao.server.print.miniappdemo.file;

import cn.iocoder.yudao.server.print.file.PdfInspection;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MiniappDemoPdfProcessInspector {
    private static final Pattern OK = Pattern.compile("(?m)^OK:([1-9][0-9]*)$");
    private static final Pattern FAILURE = Pattern.compile("(?m)^FAIL:([A-Z0-9_]{1,40})$");
    private final MiniappDemoFileProperties properties;
    private Path runtimeJar;

    public MiniappDemoPdfProcessInspector(MiniappDemoFileProperties properties) { this.properties = properties; }

    @PostConstruct
    public void initialize() throws IOException {
        if (!properties.isEnabled()) return;
        runtimeJar = properties.getRuntimeJar().toRealPath();
        if (!Files.isRegularFile(runtimeJar) || !runtimeJar.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Miniapp PDF parser requires an immutable executable jar");
        }
    }

    public int inspect(Path file, Path scratch, long scratchBytes, int maxPages, int timeoutSeconds)
            throws PdfInspection.Failure, InterruptedException {
        Path task = null;
        Process process = null;
        BoundedCollector collector = null;
        Thread collectorThread = null;
        try {
            if (runtimeJar == null) throw new PdfInspection.Failure("PARSER_UNAVAILABLE");
            task = Files.createTempDirectory(scratch, "task-");
            Files.setPosixFilePermissions(task, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xms16m", "-Xmx96m",
                    "-XX:MaxMetaspaceSize=64m", "-XX:+UseSerialGC", "-Djava.io.tmpdir=" + task,
                    "-Dloader.main=" + MiniappDemoPdfInspectorMain.class.getName(), "-cp", runtimeJar.toString(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher", file.toString(), task.toString(),
                    Long.toString(scratchBytes), Integer.toString(maxPages));
            builder.redirectErrorStream(true);
            process = builder.start();
            collector = new BoundedCollector(process.getInputStream(), 4096);
            collectorThread = new Thread(collector, "miniapp-pdf-output");
            collectorThread.setDaemon(true);
            collectorThread.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                throw new PdfInspection.Failure("PDF_TIMEOUT");
            }
            joinCollector(collectorThread, process.getInputStream());
            if (collector.overflowed() || collector.failed()) throw new PdfInspection.Failure("PARSER_UNAVAILABLE");
            String result = collector.result();
            Matcher ok = OK.matcher(result);
            if (process.exitValue() == 0 && ok.find()) return Integer.parseInt(ok.group(1));
            Matcher failed = FAILURE.matcher(result);
            if (process.exitValue() == 10 && failed.find()) throw new PdfInspection.Failure(failed.group(1));
            throw new PdfInspection.Failure("PARSER_UNAVAILABLE");
        } catch (IOException failure) {
            throw new PdfInspection.Failure("PARSER_UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            if (process != null) terminateUninterruptibly(process);
            joinCollectorUninterruptibly(collectorThread, process == null ? null : process.getInputStream());
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (process != null && process.isAlive()) terminateUninterruptibly(process);
            joinCollectorUninterruptibly(collectorThread, process == null ? null : process.getInputStream());
            if (task != null) deleteTaskDirectory(task, scratch);
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
        }
    }
    private static void terminateUninterruptibly(Process process) {
        boolean interrupted = false;
        process.destroyForcibly();
        while (process.isAlive()) {
            try { process.waitFor(); }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void joinCollector(Thread thread, InputStream input) throws InterruptedException {
        if (thread == null) return;
        thread.join(2000);
        if (thread.isAlive()) {
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            thread.join(1000);
        }
    }

    private static void joinCollectorUninterruptibly(Thread thread, InputStream input) {
        if (thread == null) return;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(100); }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (thread.isAlive()) {
            try { if (input != null) input.close(); } catch (IOException ignored) { }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static void deleteTaskDirectory(Path task, Path scratch) {
        try {
            Path root = scratch.toRealPath();
            Path candidate = task.toAbsolutePath().normalize();
            if (!candidate.getParent().equals(root) || !candidate.getFileName().toString().startsWith("task-")
                    || Files.isSymbolicLink(candidate)) return;
            Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {
        } catch (IOException ignored) {
        }
    }

    private static final class BoundedCollector implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean overflowed;
        private volatile boolean failed;
        private BoundedCollector(InputStream input, int limit) { this.input = input; this.limit = limit; }
        @Override public void run() {
            byte[] buffer = new byte[512];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int remaining = limit - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(remaining, count));
                    if (count > remaining) overflowed = true;
                }
            } catch (IOException ex) {
                failed = true;
            }
        }
        boolean overflowed() { return overflowed; }
        boolean failed() { return failed; }
        String result() { return output.toString(StandardCharsets.US_ASCII); }
    }
}
