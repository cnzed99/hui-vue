package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import org.apache.pdfbox.Loader;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Standalone synthetic checks; never reads user images or business data. */
public final class MiniappDemoImageCollageRendererChecks {
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected runtime jar path");
        Path root = Files.createTempDirectory("miniapp-collage-check-");
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        try {
            MiniappDemoProcessAdmission admission = new MiniappDemoProcessAdmission();
            MiniappDemoImageCollageProcessRenderer renderer = renderer(root, Path.of(args[0]), admission, 6_000_000);
            List<MultipartFile> fixtures = new ArrayList<>();
            for (int index = 0; index < 9; index++) {
                String format = index % 2 == 0 ? "png" : "jpg";
                byte[] bytes = image(format, index % 3 == 0 ? 90 : 160, index % 3 == 0 ? 180 : 80, index == 0, index);
                fixtures.add(new MemoryFile("fixture-" + index + "." + format, bytes));
            }
            for (int count = 1; count <= 9; count++) {
                byte[] pdf = renderer.render(new ArrayList<>(fixtures.subList(0, count)));
                try (var document = Loader.loadPDF(pdf)) {
                    check("count_" + count + "_single_a4", document.getNumberOfPages() == 1
                            && Math.abs(document.getPage(0).getMediaBox().getWidth() - 595.2756f) < 0.1f
                            && Math.abs(document.getPage(0).getMediaBox().getHeight() - 841.8898f) < 0.1f);
                }
            }
            List<MultipartFile> three = new ArrayList<>(fixtures.subList(0, 3));
            byte[] first = renderer.render(three);
            byte[] second = renderer.render(three);
            check("cross_process_deterministic", Arrays.equals(first, second));
            List<MultipartFile> swapped = new ArrayList<>(three);
            MultipartFile temporary = swapped.get(0); swapped.set(0, swapped.get(1)); swapped.set(1, temporary);
            check("ordered_content_changes_pdf", !Arrays.equals(first, renderer.render(swapped)));
            try (MiniappDemoProcessAdmission.Lease held = admission.tryAcquire()) {
                rejects("shared_admission_busy", MiniappDemoImageCollageException.Reason.RENDER_BUSY,
                        () -> renderer.render(three));
            }
            check("shared_admission_released", renderer.render(three).length > 0);
            List<MultipartFile> ten = new ArrayList<>(fixtures); ten.add(fixtures.get(0));
            rejects("ten_rejected", MiniappDemoImageCollageException.Reason.INVALID_INPUT,
                    () -> renderer.render(ten));
            rejects("fake_rejected", MiniappDemoImageCollageException.Reason.UNSUPPORTED_FORMAT,
                    () -> renderer.render(List.of(new MemoryFile("fake.png", "not-an-image".getBytes()))));
            MiniappDemoImageCollageProcessRenderer tiny = renderer(root.resolve("tiny"), Path.of(args[0]),
                    new MiniappDemoProcessAdmission(), 10);
            rejects("pixel_limit", MiniappDemoImageCollageException.Reason.PIXEL_LIMIT,
                    () -> tiny.render(List.of(fixtures.get(1))));
            check("scratch_cleaned", Files.list(root.resolve("image-collage-scratch")).findAny().isEmpty()
                    && Files.list(root.resolve("tiny/image-collage-scratch")).findAny().isEmpty());
            System.out.println("{\"status\":\"PASS\",\"passed\":" + passed + ",\"sha256\":\""
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first))
                    + "\",\"bytes\":" + first.length + "}");
        } finally {
            delete(root);
        }
    }

    private static MiniappDemoImageCollageProcessRenderer renderer(Path root, Path jar,
            MiniappDemoProcessAdmission admission, long maxPixels) throws Exception {
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        MiniappDemoFileProperties files = new MiniappDemoFileProperties();
        files.setEnabled(true); files.setStorageRoot(root); files.setRuntimeJar(jar);
        files.setMaxBytes(2 * 1024 * 1024); files.setMaxPages(20); files.setRetentionSeconds(3600);
        MiniappDemoImageCollageProperties properties = new MiniappDemoImageCollageProperties();
        properties.setEnabled(true); properties.setRenderVersion(MiniappDemoImageCollageProperties.RENDER_VERSION);
        properties.setMultipartRoot(root.resolve("multipart")); properties.setMaxMultipartBytes(13L * 1024 * 1024);
        properties.setMaxImageBytes(2L * 1024 * 1024); properties.setMaxTotalInputBytes(12L * 1024 * 1024);
        properties.setMaxPixels(maxPixels); properties.setMaxTotalPixels(30_000_000L); properties.setMaxDimension(8192);
        properties.setMaxPdfBytes(2 * 1024 * 1024); properties.setMaxConcurrentUploads(2);
        properties.setMinFreeBytes(10L * 1024 * 1024 * 1024); properties.setRenderTimeoutSeconds(15);
        MiniappDemoImageCollageProcessRenderer renderer = new MiniappDemoImageCollageProcessRenderer(properties, files, admission);
        renderer.initialize(); return renderer;
    }

    private static byte[] image(String format, int width, int height, boolean transparent, int seed) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(transparent ? new Color(255, 255, 255, 0) : Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color((seed * 37 + 50) % 255, (seed * 71 + 80) % 255,
                    (seed * 19 + 120) % 255, transparent ? 170 : 255));
            graphics.fillRect(width / 5, height / 5, Math.max(1, width * 3 / 5), Math.max(1, height * 3 / 5));
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) throw new IllegalStateException("No writer for " + format);
        return output.toByteArray();
    }

    private record MemoryFile(String originalFilename, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "files"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return null; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException(); }
    }
    private static void rejects(String name, MiniappDemoImageCollageException.Reason reason, Runnable action) {
        try { action.run(); throw new AssertionError(name); }
        catch (MiniappDemoImageCollageException failure) { check(name, failure.reason() == reason); }
    }
    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name); passed++;
    }
    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }
}
