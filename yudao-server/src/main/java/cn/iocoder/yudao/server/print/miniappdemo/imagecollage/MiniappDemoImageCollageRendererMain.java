package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Isolated multi-image decoder. Writes only one bounded, deterministic A4 PDF. */
public final class MiniappDemoImageCollageRendererMain {
    private static final int EXIT_INVALID = 20;
    private static final int EXIT_UNSUPPORTED = 21;
    private static final int EXIT_PIXEL_LIMIT = 22;
    private static final int EXIT_PDF_TOO_LARGE = 23;
    private static final int EXIT_UNAVAILABLE = 24;
    private static final int CANVAS_WIDTH = 2400;
    private static final int CANVAS_HEIGHT = 3394;
    private static final int MARGIN = 117;
    private static final int GUTTER = 33;
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private MiniappDemoImageCollageRendererMain() { }

    public static void main(String[] args) {
        int exit = EXIT_UNAVAILABLE;
        try {
            if (args.length < 10) throw new InvalidImage();
            Path output = Path.of(args[0]);
            long maxImageBytes = Long.parseLong(args[1]);
            long maxPixels = Long.parseLong(args[2]);
            long maxTotalPixels = Long.parseLong(args[3]);
            int maxDimension = Integer.parseInt(args[4]);
            int maxPdfBytes = Integer.parseInt(args[5]);
            String renderVersion = args[6];
            String orderedDigest = args[7];
            int count = Integer.parseInt(args[8]);
            if (count < 1 || count > 9 || args.length != 9 + count
                    || !MiniappDemoImageCollageProperties.RENDER_VERSION.equals(renderVersion)
                    || !orderedDigest.matches("[0-9a-f]{64}") || maxImageBytes < 1 || maxPixels < 1
                    || maxTotalPixels < maxPixels || maxDimension < 1 || maxPdfBytes < 1
                    || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new InvalidImage();
            Path[] inputs = new Path[count];
            for (int index = 0; index < count; index++) {
                inputs[index] = Path.of(args[9 + index]);
                if (Files.isSymbolicLink(inputs[index]) || !Files.isRegularFile(inputs[index], LinkOption.NOFOLLOW_LINKS)
                        || Files.size(inputs[index]) < 1 || Files.size(inputs[index]) > maxImageBytes) throw new InvalidImage();
            }
            BufferedImage canvas = renderCanvas(inputs, maxPixels, maxTotalPixels, maxDimension);
            byte[] pdf = renderPdf(canvas, renderVersion, orderedDigest, maxPdfBytes);
            try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                stream.write(pdf);
            }
            Files.setPosixFilePermissions(output, PRIVATE_FILE);
            try (FileChannel channel = FileChannel.open(output, StandardOpenOption.WRITE)) { channel.force(true); }
            System.out.println("OK:" + count + ":" + HexFormat.of().formatHex(hash(pdf)));
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

    private static BufferedImage renderCanvas(Path[] inputs, long maxPixels, long maxTotalPixels, int maxDimension)
            throws IOException {
        Grid grid = Grid.forCount(inputs.length);
        BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        long totalPixels = 0;
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cellWidth = (CANVAS_WIDTH - MARGIN * 2 - GUTTER * (grid.columns() - 1)) / grid.columns();
            int cellHeight = (CANVAS_HEIGHT - MARGIN * 2 - GUTTER * (grid.rows() - 1)) / grid.rows();
            for (int index = 0; index < inputs.length; index++) {
                Decoded decoded = decode(inputs[index], maxPixels, maxDimension);
                totalPixels = Math.addExact(totalPixels, decoded.sourcePixels());
                if (totalPixels > maxTotalPixels) throw new PixelLimit();
                BufferedImage image = decoded.image();
                double scale = Math.min(1d, Math.min((double) cellWidth / image.getWidth(), (double) cellHeight / image.getHeight()));
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int column = index % grid.columns();
                int row = index / grid.columns();
                int x = MARGIN + column * (cellWidth + GUTTER) + (cellWidth - width) / 2;
                int y = MARGIN + row * (cellHeight + GUTTER) + (cellHeight - height) / 2;
                graphics.drawImage(image, x, y, width, height, null);
                image.flush();
            }
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    private static Decoded decode(Path path, long maxPixels, int maxDimension) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (input == null) throw new InvalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new UnsupportedImage();
            ImageReader reader = readers.next();
            try {
                // Frame-count validation requires random access; seekForwardOnly would make getNumImages(true) illegal.
                reader.setInput(input, false, true);
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                boolean jpeg = "JPEG".equals(format) || "JPG".equals(format);
                boolean png = "PNG".equals(format);
                if (!jpeg && !png) throw new UnsupportedImage();
                if (png && isApng(bytes)) throw new UnsupportedImage();
                int frames;
                try { frames = reader.getNumImages(true); }
                catch (UnsupportedOperationException ignored) { frames = 1; }
                if (frames != 1) throw new UnsupportedImage();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixels = Math.multiplyExact((long) width, height);
                if (width < 1 || height < 1 || width > maxDimension || height > maxDimension || pixels > maxPixels) {
                    throw new PixelLimit();
                }
                BufferedImage raw = reader.read(0);
                if (raw == null || raw.getWidth() != width || raw.getHeight() != height) throw new InvalidImage();
                int orientation = jpeg ? exifOrientation(bytes) : 1;
                BufferedImage oriented = orient(raw, orientation);
                if (oriented != raw) raw.flush();
                return new Decoded(oriented, pixels);
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean isApng(byte[] png) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (png.length < signature.length || !Arrays.equals(signature, Arrays.copyOf(png, signature.length))) {
            throw new InvalidImage();
        }
        int offset = 8;
        while (offset + 12 <= png.length) {
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(png, offset, 4).getInt());
            if (length > Integer.MAX_VALUE || offset + 12L + length > png.length) throw new InvalidImage();
            String type = new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("acTL".equals(type)) return true;
            offset = Math.toIntExact(offset + 12L + length);
            if ("IEND".equals(type)) return false;
        }
        throw new InvalidImage();
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
        int directory = Math.toIntExact(ifdOffset), count = Short.toUnsignedInt(buffer.getShort(directory));
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

    private static byte[] renderPdf(BufferedImage canvas, String version, String orderedDigest, int maximum) throws IOException {
        byte[] digest = documentDigest(canvas, version, orderedDigest);
        try (PDDocument document = new PDDocument()) {
            byte[] id = Arrays.copyOf(digest, 16);
            COSArray ids = new COSArray(); ids.add(new COSString(id)); ids.add(new COSString(id));
            document.getDocument().setDocumentID(ids); document.setDocumentId(1L);
            PDDocumentInformation info = new PDDocumentInformation(); info.setProducer(version);
            GregorianCalendar date = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            date.clear(); date.set(2000, 0, 1, 0, 0, 0);
            info.setCreationDate(date); info.setModificationDate(date); document.setDocumentInformation(info);
            PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
            PDImageXObject embedded = JPEGFactory.createFromImage(document, canvas, 0.84f, 300);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(embedded, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            try (BoundedOutput output = new BoundedOutput(maximum)) {
                document.save(output, CompressParameters.NO_COMPRESSION); return output.toByteArray();
            }
        } finally {
            canvas.flush();
        }
    }
    private static byte[] documentDigest(BufferedImage image, String version, String orderedDigest) {
        MessageDigest digest = sha256();
        digest.update(version.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        digest.update(HexFormat.of().parseHex(orderedDigest));
        digest.update(ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
        int[] row = new int[image.getWidth()]; ByteBuffer bytes = ByteBuffer.allocate(row.length * 4);
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth()); bytes.clear();
            for (int pixel : row) bytes.putInt(pixel); digest.update(bytes.array());
        }
        return digest.digest();
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception ignored) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static byte[] hash(byte[] value) { return sha256().digest(value); }

    private record Grid(int columns, int rows) {
        static Grid forCount(int count) {
            if (count == 1) return new Grid(1, 1);
            if (count == 2) return new Grid(1, 2);
            if (count <= 4) return new Grid(2, 2);
            if (count <= 6) return new Grid(2, 3);
            if (count <= 9) return new Grid(3, 3);
            throw new InvalidImage();
        }
    }
    private record Decoded(BufferedImage image, long sourcePixels) { }
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
        private void check(int length) { if (length < 0 || bytes.size() > maximum - length) throw new PdfTooLarge(); }
        byte[] toByteArray() { return bytes.toByteArray(); }
        @Override public void close() throws IOException { bytes.close(); }
    }
}
