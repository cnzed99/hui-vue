package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/** Isolated child-process decoder. Writes only a bounded normalized single-page PDF. */
public final class MiniappDemoImageRendererMain {
    private static final int EXIT_INVALID = 20;
    private static final int EXIT_UNSUPPORTED = 21;
    private static final int EXIT_PIXEL_LIMIT = 22;
    private static final int EXIT_PDF_TOO_LARGE = 23;
    private static final int EXIT_UNAVAILABLE = 24;
    private static final int MAX_RENDER_WIDTH = 2400;
    private static final int MAX_RENDER_HEIGHT = 3394;
    private static final float PAGE_MARGIN = 28f;

    private MiniappDemoImageRendererMain() { }

    public static void main(String[] args) {
        int exit = EXIT_UNAVAILABLE;
        try {
            if (args.length != 6) throw new InvalidImage();
            Path input = Path.of(args[0]);
            Path output = Path.of(args[1]);
            long maxPixels = Long.parseLong(args[2]);
            int maxDimension = Integer.parseInt(args[3]);
            int maxPdfBytes = Integer.parseInt(args[4]);
            String renderVersion = args[5];
            if (!MiniappDemoImageProperties.RENDER_VERSION.equals(renderVersion)
                    || maxPixels < 1 || maxDimension < 1 || maxPdfBytes < 1
                    || Files.isSymbolicLink(input) || !Files.isRegularFile(input)
                    || Files.exists(output)) throw new InvalidImage();
            byte[] sourceBytes = Files.readAllBytes(input);
            Decoded decoded = decode(sourceBytes, maxPixels, maxDimension);
            byte[] pdf = render(decoded.image(), renderVersion, maxPdfBytes);
            try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                stream.write(pdf);
            }
            System.out.println("OK:" + decoded.format() + ":" + decoded.image().getWidth() + "x" + decoded.image().getHeight()
                    + ":" + HexFormat.of().formatHex(hash(pdf)));
            exit = 0;
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

    private static Decoded decode(byte[] bytes, long maxPixels, int maxDimension) throws IOException {
        if (bytes.length == 0) throw new InvalidImage();
        try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (input == null) throw new InvalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new UnsupportedImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!("JPEG".equals(format) || "JPG".equals(format) || "PNG".equals(format))) throw new UnsupportedImage();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > maxDimension || height > maxDimension
                        || (long) width * height > maxPixels) throw new PixelLimit();
                BufferedImage raw = reader.read(0);
                if (raw == null || raw.getWidth() != width || raw.getHeight() != height) throw new InvalidImage();
                int orientation = format.startsWith("JP") ? exifOrientation(bytes) : 1;
                BufferedImage oriented = orient(raw, orientation);
                BufferedImage normalized = normalize(oriented);
                return new Decoded(normalized, format.startsWith("JP") ? "JPEG" : "PNG");
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage normalize(BufferedImage source) {
        double scale = Math.min(1d, Math.min((double) MAX_RENDER_WIDTH / source.getWidth(),
                (double) MAX_RENDER_HEIGHT / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        if (orientation < 2 || orientation > 8) throw new InvalidImage();
        int width = source.getWidth(), height = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage result = new BufferedImage(swap ? height : width, swap ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] sourcePixels = source.getRGB(0, 0, width, height, null, 0, width);
        int[] targetPixels = new int[sourcePixels.length];
        int targetWidth = result.getWidth();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
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
        }
        result.setRGB(0, 0, result.getWidth(), result.getHeight(), targetPixels, 0, result.getWidth());
        return result;
    }

    private static int exifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) throw new InvalidImage();
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if ((jpeg[offset] & 0xff) != 0xff) throw new InvalidImage();
            int marker = jpeg[offset + 1] & 0xff;
            offset += 2;
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
            int tag = Short.toUnsignedInt(buffer.getShort(entry));
            if (tag != 0x0112) continue;
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

    private static byte[] render(BufferedImage image, String version, int maximum) throws IOException {
        byte[] digest = imageDigest(image, version);
        try (PDDocument document = new PDDocument()) {
            byte[] id = Arrays.copyOf(digest, 16);
            COSArray ids = new COSArray(); ids.add(new COSString(id)); ids.add(new COSString(id));
            document.getDocument().setDocumentID(ids);
            document.setDocumentId(1L);
            PDDocumentInformation info = new PDDocumentInformation();
            info.setProducer(version);
            GregorianCalendar date = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            date.clear(); date.set(2000, 0, 1, 0, 0, 0);
            info.setCreationDate(date); info.setModificationDate(date);
            document.setDocumentInformation(info);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject embedded = JPEGFactory.createFromImage(document, image, 0.90f, 300);
            float availableWidth = PDRectangle.A4.getWidth() - PAGE_MARGIN * 2;
            float availableHeight = PDRectangle.A4.getHeight() - PAGE_MARGIN * 2;
            float scale = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
            float width = image.getWidth() * scale;
            float height = image.getHeight() * scale;
            float x = (PDRectangle.A4.getWidth() - width) / 2;
            float y = (PDRectangle.A4.getHeight() - height) / 2;
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(embedded, x, y, width, height);
            }
            try (BoundedOutput output = new BoundedOutput(maximum)) {
                document.save(output, CompressParameters.NO_COMPRESSION);
                return output.toByteArray();
            }
        }
    }

    private static byte[] imageDigest(BufferedImage image, String version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(version.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
            int[] row = new int[image.getWidth()];
            ByteBuffer bytes = ByteBuffer.allocate(row.length * 4);
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                bytes.clear();
                for (int pixel : row) bytes.putInt(pixel);
                digest.update(bytes.array());
            }
            return digest.digest();
        } catch (Exception ignored) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception ignored) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private record Decoded(BufferedImage image, String format) { }
    private static final class InvalidImage extends RuntimeException { }
    private static final class UnsupportedImage extends RuntimeException { }
    private static final class PixelLimit extends RuntimeException { }
    private static final class PdfTooLarge extends RuntimeException { }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum;
        private BoundedOutput(int maximum) { this.maximum = maximum; }
        @Override public void write(int value) { check(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) { check(length); bytes.write(value, offset, length); }
        private void check(int length) {
            if (length < 0 || bytes.size() > maximum - length) throw new PdfTooLarge();
        }
        byte[] toByteArray() { return bytes.toByteArray(); }
        @Override public void close() throws IOException { bytes.close(); }
    }
}
