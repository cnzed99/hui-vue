package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoImageCollageException.Reason.*;

@Component
public final class MiniappDemoImageCollageProcessRenderer {
    private static final Pattern OK = Pattern.compile("(?m)^OK:([1-9]):([0-9a-f]{64})$");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final MiniappDemoImageCollageProperties properties;
    private final MiniappDemoFileProperties fileProperties;
    private final MiniappDemoProcessAdmission processAdmission;
    private Path runtimeJar;
    private Path scratch;

    public MiniappDemoImageCollageProcessRenderer(MiniappDemoImageCollageProperties properties,
            MiniappDemoFileProperties fileProperties, MiniappDemoProcessAdmission processAdmission) {
        this.properties = properties; this.fileProperties = fileProperties; this.processAdmission = processAdmission;
    }

    @PostConstruct
    public void initialize() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        if (!fileProperties.isEnabled() || properties.getMaxPdfBytes() > fileProperties.getMaxBytes()) {
            throw new IOException("Image collage limits must fit the miniapp demo file limits");
        }
        runtimeJar = fileProperties.getRuntimeJar().toRealPath();
        if (!Files.isRegularFile(runtimeJar) || !runtimeJar.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Image collage renderer requires an immutable executable jar");
        }
        scratch = privateDirectory(fileProperties.getStorageRoot().normalize().resolve("image-collage-scratch"));
        cleanupAbandoned();
    }

    public byte[] render(List<MultipartFile> images) {
        requireEnabled();
        if (images == null || images.isEmpty() || images.size() > 9 || images.stream().anyMatch(image -> image == null || image.isEmpty())) {
            throw failure(INVALID_INPUT);
        }
        Path task = null;
        Process process = null;
        Collector collector = null;
        Thread collectorThread = null;
        MiniappDemoProcessAdmission.Lease lease = null;
        try {
            task = Files.createTempDirectory(scratch, "task-");
            Files.setPosixFilePermissions(task, PRIVATE_DIRECTORY);
            List<Path> inputs = new ArrayList<>(images.size());
            List<Content> contents = new ArrayList<>(images.size());
            long[] total = {0};
            for (int index = 0; index < images.size(); index++) {
                MultipartFile image = images.get(index);
                if (image.getSize() < 1 || image.getSize() > properties.getMaxImageBytes()) throw failure(INPUT_TOO_LARGE);
                Path input = task.resolve("input-" + index + ".bin");
                Content content = copyAndDigest(image, input, total);
                inputs.add(input); contents.add(content);
            }
            String orderedDigest = orderedDigest(contents);
            lease = processAdmission.tryAcquire();
            if (lease == null) throw failure(RENDER_BUSY);
            Path output = task.resolve("output.pdf");
            List<String> command = new ArrayList<>();
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            command.addAll(List.of(java.toString(), "-Xms32m", "-Xmx320m", "-XX:MaxMetaspaceSize=96m",
                    "-XX:+UseSerialGC", "-Djava.io.tmpdir=" + task,
                    "-Dloader.main=" + MiniappDemoImageCollageRendererMain.class.getName(), "-cp", runtimeJar.toString(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher", output.toString(),
                    Long.toString(properties.getMaxImageBytes()), Long.toString(properties.getMaxPixels()),
                    Long.toString(properties.getMaxTotalPixels()), Integer.toString(properties.getMaxDimension()),
                    Integer.toString(properties.getMaxPdfBytes()), properties.getRenderVersion(), orderedDigest,
                    Integer.toString(inputs.size())));
            inputs.forEach(input -> command.add(input.toString()));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            collector = new Collector(process.getInputStream(), 2048);
            collectorThread = new Thread(collector, "miniapp-image-collage-output");
            collectorThread.setDaemon(true); collectorThread.start();
            if (!process.waitFor(properties.getRenderTimeoutSeconds(), TimeUnit.SECONDS)) {
                terminate(process); throw failure(RENDER_TIMEOUT);
            }
            join(collectorThread, process.getInputStream());
            if (collector.failed || collector.overflowed) throw failure(RENDER_UNAVAILABLE);
            var success = OK.matcher(collector.result().strip());
            int reportedCount = -1;
            String reportedDigest = null;
            boolean exactlyOne = false;
            if (success.find()) {
                reportedCount = Integer.parseInt(success.group(1));
                reportedDigest = success.group(2);
                exactlyOne = !success.find();
            }
            if (process.exitValue() == 0 && exactlyOne && reportedCount == images.size()
                    && Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output)) {
                byte[] pdf = Files.readAllBytes(output);
                if (pdf.length < 1 || pdf.length > properties.getMaxPdfBytes()) throw failure(PDF_TOO_LARGE);
                if (!HexFormat.of().formatHex(hash(pdf)).equals(reportedDigest)) throw failure(RENDER_UNAVAILABLE);
                return pdf;
            }
            throw switch (process.exitValue()) {
                case 20 -> failure(INVALID_INPUT);
                case 21 -> failure(UNSUPPORTED_FORMAT);
                case 22 -> failure(PIXEL_LIMIT);
                case 23 -> failure(PDF_TOO_LARGE);
                default -> failure(RENDER_UNAVAILABLE);
            };
        } catch (MiniappDemoImageCollageException failure) {
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

    private Content copyAndDigest(MultipartFile image, Path target, long[] total) throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        try (InputStream input = image.getInputStream(); OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(target, PRIVATE_FILE);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (count > properties.getMaxImageBytes() - read
                        || total[0] > properties.getMaxTotalInputBytes() - read) throw failure(INPUT_TOO_LARGE);
                count += read; total[0] += read;
                output.write(buffer, 0, read); digest.update(buffer, 0, read);
            }
        }
        if (count == 0) throw failure(INVALID_INPUT);
        try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
        return new Content(count, digest.digest());
    }

    private String orderedDigest(List<Content> contents) {
        MessageDigest digest = sha256();
        digest.update(properties.getRenderVersion().getBytes(StandardCharsets.US_ASCII));
        digest.update(ByteBuffer.allocate(4).putInt(contents.size()).array());
        for (Content content : contents) {
            digest.update(ByteBuffer.allocate(8).putLong(content.size()).array());
            digest.update(content.digest());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || runtimeJar == null || scratch == null) throw failure(DISABLED);
    }
    private static MiniappDemoImageCollageException failure(MiniappDemoImageCollageException.Reason reason) {
        return new MiniappDemoImageCollageException(reason);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception ignored) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static byte[] hash(byte[] value) { return sha256().digest(value); }
    private static Path privateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path); Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY);
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path).equals(PRIVATE_DIRECTORY)) throw new IOException("Unsafe collage scratch directory");
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

    private record Content(long size, byte[] digest) { }
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
