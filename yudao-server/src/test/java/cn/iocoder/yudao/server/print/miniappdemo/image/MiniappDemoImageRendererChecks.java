package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import org.apache.pdfbox.Loader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** Standalone synthetic checks; never reads user images or business data. */
public final class MiniappDemoImageRendererChecks {
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected runtime jar path");
        Path root = Files.createTempDirectory("miniapp-image-check-");
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        try {
            byte[] transparentPng = image("png", 160, 80, true);
            byte[] jpeg = image("jpg", 80, 160, false);
            MiniappDemoImageProcessRenderer renderer = renderer(root, Path.of(args[0]), 12_000_000);
            byte[] first = renderer.render(new ByteArrayInputStream(transparentPng), transparentPng.length);
            byte[] second = renderer.render(new ByteArrayInputStream(transparentPng), transparentPng.length);
            check("cross_process_deterministic", Arrays.equals(first, second));
            try (var document = Loader.loadPDF(first)) {
                check("single_page", document.getNumberOfPages() == 1);
                var box = document.getPage(0).getMediaBox();
                check("a4_portrait", Math.abs(box.getWidth() - 595.2756f) < 0.1f
                        && Math.abs(box.getHeight() - 841.8898f) < 0.1f);
            }
            byte[] jpegPdf = renderer.render(new ByteArrayInputStream(jpeg), jpeg.length);
            check("jpeg_supported", jpegPdf.length > 0);
            rejects("invalid_rejected", MiniappDemoImageException.Reason.UNSUPPORTED_FORMAT,
                    () -> renderer.render(new ByteArrayInputStream("not-an-image".getBytes()), 12));
            MiniappDemoImageProcessRenderer tiny = renderer(root.resolve("tiny"), Path.of(args[0]), 10);
            rejects("pixel_limit", MiniappDemoImageException.Reason.PIXEL_LIMIT,
                    () -> tiny.render(new ByteArrayInputStream(jpeg), jpeg.length));
            check("scratch_cleaned", Files.list(root.resolve("image-scratch")).findAny().isEmpty()
                    && Files.list(root.resolve("tiny/image-scratch")).findAny().isEmpty());
            System.out.println("{\"status\":\"PASS\",\"passed\":" + passed + ",\"sha256\":\""
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first))
                    + "\",\"bytes\":" + first.length + "}");
        } finally {
            delete(root);
        }
    }

    private static MiniappDemoImageProcessRenderer renderer(Path root, Path jar, long maxPixels) throws Exception {
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        MiniappDemoFileProperties files = new MiniappDemoFileProperties();
        files.setEnabled(true); files.setStorageRoot(root); files.setRuntimeJar(jar);
        files.setMaxBytes(2 * 1024 * 1024); files.setMaxPages(20); files.setRetentionSeconds(3600);
        MiniappDemoImageProperties images = new MiniappDemoImageProperties();
        images.setEnabled(true); images.setRenderVersion(MiniappDemoImageProperties.RENDER_VERSION);
        images.setMaxInputBytes(2 * 1024 * 1024); images.setMaxPixels(maxPixels); images.setMaxDimension(8192);
        images.setMaxPdfBytes(2 * 1024 * 1024); images.setMaxConcurrentRenders(1); images.setRenderTimeoutSeconds(10);
        MiniappDemoImageProcessRenderer renderer = new MiniappDemoImageProcessRenderer(
                images, files, new MiniappDemoProcessAdmission());
        renderer.initialize(); return renderer;
    }

    private static byte[] image(String format, int width, int height, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            if (transparent) {
                graphics.setColor(new Color(255, 255, 255, 0)); graphics.fillRect(0, 0, width, height);
            } else {
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            }
            graphics.setColor(new Color(255, 120, 30, transparent ? 180 : 255));
            graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) throw new IllegalStateException("No writer for " + format);
        return output.toByteArray();
    }

    private static void rejects(String name, MiniappDemoImageException.Reason reason, Runnable action) {
        try { action.run(); throw new AssertionError(name); }
        catch (MiniappDemoImageException failure) { check(name, failure.reason() == reason); }
    }
    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name); passed++;
    }
    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }
}
