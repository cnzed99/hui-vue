package cn.iocoder.yudao.server.print.miniappdemo.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** Isolated child-JVM checks for the server-side id-photo decoder and PDF renderer. */
public final class MiniappDemoIdPhotoServerChecks {
    private MiniappDemoIdPhotoServerChecks() { }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        Path root = Path.of(args[1]).toRealPath();
        Path scratch = Files.createTempDirectory(root, "id-photo-server-");
        try {
            Path jpeg = image(scratch.resolve("opaque.jpg"), "JPEG", BufferedImage.TYPE_INT_RGB, 360, 480);
            Path png = image(scratch.resolve("opaque.png"), "PNG", BufferedImage.TYPE_INT_RGB, 360, 480);
            Path alpha = image(scratch.resolve("alpha.png"), "PNG", BufferedImage.TYPE_INT_ARGB, 360, 480);
            Path gif = image(scratch.resolve("unsupported.gif"), "GIF", BufferedImage.TYPE_INT_RGB, 64, 64);
            Path wide = image(scratch.resolve("wide.png"), "PNG", BufferedImage.TYPE_INT_RGB, 2401, 1);
            Path animated = animatedPng(png, scratch.resolve("animated.png"));

            Result jpegResult = run(jar, scratch, jpeg, "jpeg-output.pdf", "ONE_INCH");
            check(jpegResult.exitCode == 0 && jpegResult.output.matches(
                    "(?s).*OK:ONE_INCH:JPEG:360x480:[0-9a-f]{64}.*"), "opaque JPEG success receipt");
            checkPdf(scratch.resolve("jpeg-output.pdf"));

            Result pngResult = run(jar, scratch, png, "png-output.pdf", "THREE_INCH");
            check(pngResult.exitCode == 0 && pngResult.output.matches(
                    "(?s).*OK:THREE_INCH:PNG:360x480:[0-9a-f]{64}.*"), "opaque PNG success receipt");
            checkPdf(scratch.resolve("png-output.pdf"));

            check(run(jar, scratch, alpha, "alpha-output.pdf", "ONE_INCH").exitCode == 20,
                    "alpha input rejected");
            check(run(jar, scratch, gif, "gif-output.pdf", "ONE_INCH").exitCode == 21,
                    "unsupported format rejected");
            check(run(jar, scratch, animated, "animated-output.pdf", "ONE_INCH").exitCode == 21,
                    "animated PNG rejected");
            check(run(jar, scratch, wide, "wide-output.pdf", "ONE_INCH").exitCode == 22,
                    "dimension limit enforced");
            System.out.println("ID_PHOTO_SERVER_CHILD_PROCESS_PASS jpeg png alpha gif apng dimensions");
        } finally {
            deleteTree(scratch);
        }
    }

    private static Path animatedPng(Path source, Path target) throws Exception {
        byte[] png = Files.readAllBytes(source);
        check(png.length > 33, "PNG fixture length");
        byte[] type = "acTL".getBytes(StandardCharsets.US_ASCII);
        byte[] data = {0, 0, 0, 2, 0, 0, 0, 0};
        CRC32 crc = new CRC32();
        crc.update(type); crc.update(data);
        var chunk = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.BIG_ENDIAN);
        chunk.putInt(data.length).put(type).put(data).putInt((int) crc.getValue());
        byte[] result = new byte[png.length + chunk.capacity()];
        System.arraycopy(png, 0, result, 0, 33);
        System.arraycopy(chunk.array(), 0, result, 33, chunk.capacity());
        System.arraycopy(png, 33, result, 33 + chunk.capacity(), png.length - 33);
        Files.write(target, result);
        return target;
    }

    private static Path image(Path path, String format, int type, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, type);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(245, 245, 245));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(40, 80, 180, type == BufferedImage.TYPE_INT_ARGB ? 120 : 255));
            graphics.fillRect(0, 0, Math.max(1, width / 3), Math.max(1, height / 3));
        } finally { graphics.dispose(); }
        check(ImageIO.write(image, format, path.toFile()), "fixture encoder " + format);
        return path;
    }

    private static Result run(Path jar, Path scratch, Path input, String outputName, String variant) throws Exception {
        Path output = scratch.resolve(outputName);
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC",
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + scratch,
                "-Dloader.main=" + MiniappDemoIdPhotoRendererMain.class.getName(),
                "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher",
                input.toString(), output.toString(), variant,
                Long.toString(MiniappDemoIdPhotoProperties.MAX_INPUT_BYTES),
                Integer.toString(MiniappDemoIdPhotoProperties.MAX_WIDTH),
                Integer.toString(MiniappDemoIdPhotoProperties.MAX_HEIGHT),
                Long.toString(MiniappDemoIdPhotoProperties.MAX_PIXELS),
                Integer.toString(MiniappDemoIdPhotoProperties.MAX_PDF_BYTES),
                MiniappDemoIdPhotoProperties.RENDER_VERSION).redirectErrorStream(true).start();
        var bytes = new ByteArrayOutputStream();
        Thread collector = new Thread(() -> {
            try (var inputStream = process.getInputStream()) { inputStream.transferTo(bytes); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }, "id-photo-server-check-output");
        collector.setDaemon(true);
        collector.start();
        check(process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS), "child timeout");
        collector.join(3000);
        check(!collector.isAlive(), "child output drained");
        return new Result(process.exitValue(), bytes.toString(StandardCharsets.US_ASCII));
    }

    private static void checkPdf(Path path) throws Exception {
        byte[] pdf = Files.readAllBytes(path);
        check(pdf.length > 8 && pdf.length <= MiniappDemoIdPhotoProperties.MAX_PDF_BYTES, "PDF size");
        check(Arrays.equals(Arrays.copyOf(pdf, 5), "%PDF-".getBytes(StandardCharsets.US_ASCII)), "PDF header");
        String tail = new String(pdf, Math.max(0, pdf.length - 1024), Math.min(1024, pdf.length),
                StandardCharsets.ISO_8859_1);
        check(tail.contains("%%EOF"), "PDF trailer");
    }

    private static void deleteTree(Path root) throws Exception {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, java.io.IOException failure)
                    throws java.io.IOException {
                if (failure != null) throw failure;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private record Result(int exitCode, String output) { }
}
