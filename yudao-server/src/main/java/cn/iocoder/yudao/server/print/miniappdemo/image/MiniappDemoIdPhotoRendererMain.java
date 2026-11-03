package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileVariant;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;

public final class MiniappDemoIdPhotoRendererMain {
    private static final int EXIT_INVALID = 20;
    private static final int EXIT_UNSUPPORTED = 21;
    private static final int EXIT_PIXEL_LIMIT = 22;
    private static final int EXIT_PDF_TOO_LARGE = 23;
    private static final int EXIT_UNAVAILABLE = 24;

    private MiniappDemoIdPhotoRendererMain() { }

    public static void main(String[] args) {
        int exit = EXIT_UNAVAILABLE;
        try {
            if (args.length != 9) throw new InvalidImage();
            Path input = Path.of(args[0]);
            Path output = Path.of(args[1]);
            MiniappDemoFileVariant variant = MiniappDemoFileVariant.valueOf(args[2]);
            long maxInputBytes = Long.parseLong(args[3]);
            int maxWidth = Integer.parseInt(args[4]);
            int maxHeight = Integer.parseInt(args[5]);
            long maxPixels = Long.parseLong(args[6]);
            int maxPdfBytes = Integer.parseInt(args[7]);
            String version = args[8];
            if (!MiniappDemoIdPhotoProperties.RENDER_VERSION.equals(version)
                    || maxInputBytes != MiniappDemoIdPhotoProperties.MAX_INPUT_BYTES
                    || maxWidth != MiniappDemoIdPhotoProperties.MAX_WIDTH
                    || maxHeight != MiniappDemoIdPhotoProperties.MAX_HEIGHT
                    || maxPixels != MiniappDemoIdPhotoProperties.MAX_PIXELS
                    || maxPdfBytes != MiniappDemoIdPhotoProperties.MAX_PDF_BYTES
                    || Files.isSymbolicLink(input) || !Files.isRegularFile(input)
                    || Files.size(input) < 1 || Files.size(input) > maxInputBytes || Files.exists(output)) throw new InvalidImage();
            Decoded decoded = decode(Files.readAllBytes(input), maxWidth, maxHeight, maxPixels);
            byte[] pdf = MiniappDemoIdPhotoPdfRenderer.render(decoded.image(),
                    MiniappDemoIdPhotoPdfRenderer.Specification.valueOf(variant.name()));
            if (pdf.length < 1 || pdf.length > maxPdfBytes) throw new PdfTooLarge();
            try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                stream.write(pdf);
            }
            System.out.println("OK:" + variant.name() + ":" + decoded.format() + ":"
                    + decoded.image().getWidth() + "x" + decoded.image().getHeight() + ":"
                    + HexFormat.of().formatHex(hash(pdf)));
            exit = 0;
        } catch (IllegalArgumentException failure) {
            if (MiniappDemoIdPhotoPdfRenderer.OUTPUT_LIMIT_MESSAGE.equals(failure.getMessage())) {
                System.out.println("FAIL:PDF_TOO_LARGE"); exit = EXIT_PDF_TOO_LARGE;
            } else if (MiniappDemoIdPhotoPdfRenderer.INPUT_LIMIT_MESSAGE.equals(failure.getMessage())) {
                System.out.println("FAIL:PIXEL_LIMIT"); exit = EXIT_PIXEL_LIMIT;
            } else {
                System.out.println("FAIL:INVALID_IMAGE"); exit = EXIT_INVALID;
            }
        } catch (UnsupportedImage ignored) {
            System.out.println("FAIL:UNSUPPORTED_FORMAT"); exit = EXIT_UNSUPPORTED;
        } catch (PixelLimit ignored) {
            System.out.println("FAIL:PIXEL_LIMIT"); exit = EXIT_PIXEL_LIMIT;
        } catch (PdfTooLarge ignored) {
            System.out.println("FAIL:PDF_TOO_LARGE"); exit = EXIT_PDF_TOO_LARGE;
        } catch (InvalidImage ignored) {
            System.out.println("FAIL:INVALID_IMAGE"); exit = EXIT_INVALID;
        } catch (Exception ignored) {
            System.out.println("FAIL:RENDER_UNAVAILABLE"); exit = EXIT_UNAVAILABLE;
        }
        System.exit(exit);
    }

    private static Decoded decode(byte[] bytes, int maxWidth, int maxHeight, long maxPixels) throws Exception {
        if (bytes.length == 0) throw new InvalidImage();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new InvalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new UnsupportedImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!("JPEG".equals(format) || "JPG".equals(format) || "PNG".equals(format))) throw new UnsupportedImage();
                if (reader.getNumImages(true) != 1) throw new UnsupportedImage();
                int encodedWidth = reader.getWidth(0), encodedHeight = reader.getHeight(0);
                if (encodedWidth < 1 || encodedHeight < 1 || encodedWidth > Math.max(maxWidth, maxHeight)
                        || encodedHeight > Math.max(maxWidth, maxHeight)
                        || (long) encodedWidth * encodedHeight > maxPixels) throw new PixelLimit();
                BufferedImage raw = reader.read(0);
                if (raw == null || raw.getWidth() != encodedWidth || raw.getHeight() != encodedHeight
                        || raw.getColorModel().hasAlpha()) throw new InvalidImage();
                int orientation = format.startsWith("JP") ? exifOrientation(bytes) : 1;
                BufferedImage oriented = orient(raw, orientation);
                if (oriented.getWidth() > maxWidth || oriented.getHeight() > maxHeight
                        || (long) oriented.getWidth() * oriented.getHeight() > maxPixels) throw new PixelLimit();
                BufferedImage normalized = new BufferedImage(oriented.getWidth(), oriented.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = normalized.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(oriented, 0, 0, null);
                } finally { graphics.dispose(); }
                return new Decoded(normalized, format.startsWith("JP") ? "JPEG" : "PNG");
            } finally { reader.dispose(); }
        }
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        if (orientation < 2 || orientation > 8) throw new InvalidImage();
        int width = source.getWidth(), height = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage result = new BufferedImage(swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_RGB);
        int[] sourcePixels = source.getRGB(0, 0, width, height, null, 0, width);
        int[] targetPixels = new int[sourcePixels.length];
        int targetWidth = result.getWidth();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int dx, dy;
            switch (orientation) {
                case 2 -> { dx = width - 1 - x; dy = y; }
                case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                case 4 -> { dx = x; dy = height - 1 - y; }
                case 5 -> { dx = y; dy = x; }
                case 6 -> { dx = height - 1 - y; dy = x; }
                case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                case 8 -> { dx = y; dy = width - 1 - x; }
                default -> throw new InvalidImage();
            }
            targetPixels[dy * targetWidth + dx] = sourcePixels[y * width + x];
        }
        result.setRGB(0, 0, result.getWidth(), result.getHeight(), targetPixels, 0, result.getWidth());
        return result;
    }

    private static int exifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) throw new InvalidImage();
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if ((jpeg[offset] & 0xff) != 0xff) throw new InvalidImage();
            int marker = jpeg[offset + 1] & 0xff; offset += 2;
            if (marker == 0xd9 || marker == 0xda) return 1;
            if (marker == 0x00 || marker == 0xff || marker >= 0xd0 && marker <= 0xd7 || marker == 0x01) continue;
            if (offset + 2 > jpeg.length) throw new InvalidImage();
            int length = ((jpeg[offset] & 0xff) << 8) | (jpeg[offset + 1] & 0xff);
            if (length < 2 || offset + length > jpeg.length) throw new InvalidImage();
            if (marker == 0xe1 && length >= 8 && equalsAscii(jpeg, offset + 2, "Exif\0\0")) {
                return parseTiffOrientation(jpeg, offset + 8, length - 8);
            }
            offset += length;
        }
        return 1;
    }

    private static int parseTiffOrientation(byte[] bytes, int start, int length) {
        if (length < 8 || start < 0 || start + length > bytes.length) throw new InvalidImage();
        ByteOrder order;
        if (bytes[start] == 'I' && bytes[start + 1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
        else if (bytes[start] == 'M' && bytes[start + 1] == 'M') order = ByteOrder.BIG_ENDIAN;
        else throw new InvalidImage();
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, length).slice().order(order);
        if (Short.toUnsignedInt(buffer.getShort(2)) != 42) throw new InvalidImage();
        long ifdOffset = Integer.toUnsignedLong(buffer.getInt(4));
        if (ifdOffset > length - 2) throw new InvalidImage();
        int directory = Math.toIntExact(ifdOffset);
        int count = Short.toUnsignedInt(buffer.getShort(directory));
        if ((long) directory + 2L + count * 12L > length) throw new InvalidImage();
        for (int index = 0; index < count; index++) {
            int entry = directory + 2 + index * 12;
            if (Short.toUnsignedInt(buffer.getShort(entry)) != 0x0112) continue;
            int type = Short.toUnsignedInt(buffer.getShort(entry + 2));
            long values = Integer.toUnsignedLong(buffer.getInt(entry + 4));
            if (type != 3 || values != 1) throw new InvalidImage();
            int orientation = Short.toUnsignedInt(buffer.getShort(entry + 8));
            if (orientation < 1 || orientation > 8) throw new InvalidImage();
            return orientation;
        }
        return 1;
    }

    private static boolean equalsAscii(byte[] value, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > value.length) return false;
        for (int index = 0; index < expected.length(); index++) {
            if ((byte) expected.charAt(index) != value[offset + index]) return false;
        }
        return true;
    }

    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    private record Decoded(BufferedImage image, String format) { }
    private static final class InvalidImage extends RuntimeException { }
    private static final class UnsupportedImage extends RuntimeException { }
    private static final class PixelLimit extends RuntimeException { }
    private static final class PdfTooLarge extends RuntimeException { }
}
