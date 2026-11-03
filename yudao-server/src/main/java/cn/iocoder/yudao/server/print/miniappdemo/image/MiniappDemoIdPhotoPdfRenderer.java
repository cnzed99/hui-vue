package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.util.Matrix;

import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Offline layout for an already decoded, oriented RGB photo. */
final class MiniappDemoIdPhotoPdfRenderer {
    private static final double PAGE_WIDTH_MM = 152;
    private static final double PAGE_HEIGHT_MM = 102;
    private static final double POINTS_PER_MM = 72.0 / 25.4;
    private static final int TARGET_DPI = 300;
    private static final String RENDER_VERSION = "id-photo-6in-v2";
    private static final int[] JPEG_QUALITY_PERCENT = {92, 88, 84};
    static final int MAX_PDF_BYTES = 2 * 1024 * 1024;
    static final String OUTPUT_LIMIT_MESSAGE = "Photo PDF exceeds the output limit";
    static final int MAX_WIDTH = 2400;
    static final int MAX_HEIGHT = 3394;
    static final long MAX_PIXELS = (long) MAX_WIDTH * MAX_HEIGHT;
    static final String INPUT_LIMIT_MESSAGE = "Photo dimensions exceed the normalized RGB limit";

    enum Specification {
        ONE_INCH(25, 35, 5, 2, false),
        TWO_INCH(35, 49, 2, 2, true),
        THREE_INCH(55, 84, 2, 1, false);

        private final int photoWidth;
        private final int photoHeight;
        private final int columns;
        private final int rows;
        private final boolean rotateClockwise;

        Specification(int photoWidth, int photoHeight, int columns, int rows, boolean rotateClockwise) {
            this.photoWidth = photoWidth;
            this.photoHeight = photoHeight;
            this.columns = columns;
            this.rows = rows;
            this.rotateClockwise = rotateClockwise;
        }

        private double cellWidth() { return rotateClockwise ? photoHeight : photoWidth; }
        private double cellHeight() { return rotateClockwise ? photoWidth : photoHeight; }
    }

    private MiniappDemoIdPhotoPdfRenderer() { }

    static byte[] render(BufferedImage image, Specification specification) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(specification, "specification");
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException(INPUT_LIMIT_MESSAGE);
        }
        if (image.getColorModel().hasAlpha() || image.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_RGB) {
            throw new IllegalArgumentException("An opaque RGB photo is required");
        }
        BufferedImage embedded = fitToTargetDpi(image, specification);
        for (int quality : JPEG_QUALITY_PERCENT) {
            try {
                return encode(embedded, image.getWidth(), image.getHeight(), specification, quality);
            } catch (PdfLimitExceeded ignored) {
                // Retry the same bounded pixels with the next fixed quality setting.
            }
        }
        throw new IllegalArgumentException(OUTPUT_LIMIT_MESSAGE);
    }

    private static BufferedImage fitToTargetDpi(BufferedImage source, Specification specification) {
        int maxWidth = (int) Math.ceil(specification.photoWidth * TARGET_DPI / 25.4);
        int maxHeight = (int) Math.ceil(specification.photoHeight * TARGET_DPI / 25.4);
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxWidth && height <= maxHeight) return source;
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        int targetWidth = Math.min(maxWidth, (int) Math.ceil(width * scale));
        int targetHeight = Math.min(maxHeight, (int) Math.ceil(height * scale));
        var result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] encode(BufferedImage image, int sourceWidth, int sourceHeight,
            Specification specification, int quality) throws IOException {
        double cellWidth = specification.cellWidth();
        double cellHeight = specification.cellHeight();
        double left = (PAGE_WIDTH_MM - cellWidth * specification.columns) / 2;
        double bottom = (PAGE_HEIGHT_MM - cellHeight * specification.rows) / 2;

        try (PDDocument document = new PDDocument()) {
            setStableDocumentId(document, image, sourceWidth, sourceHeight, specification, quality);
            document.getDocumentInformation().setProducer(RENDER_VERSION + "/jpeg-" + quality + "/"
                    + image.getWidth() + "x" + image.getHeight());
            PDPage page = new PDPage(new PDRectangle(points(PAGE_WIDTH_MM), points(PAGE_HEIGHT_MM)));
            page.setRotation(0);
            document.addPage(page);
            PDImageXObject photo = JPEGFactory.createFromImage(document, image, quality / 100f, TARGET_DPI);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                for (int row = 0; row < specification.rows; row++) {
                    for (int column = 0; column < specification.columns; column++) {
                        drawPhoto(stream, photo, sourceWidth, sourceHeight, specification,
                                left + column * cellWidth, bottom + row * cellHeight);
                    }
                }
                drawCutLines(stream, specification, left, bottom);
            }
            var output = new BoundedOutput();
            document.save(output, CompressParameters.NO_COMPRESSION);
            return output.toByteArray();
        }
    }

    private static void drawPhoto(PDPageContentStream stream, PDImageXObject photo, int sourceWidth, int sourceHeight,
            Specification specification, double cellX, double cellY) throws IOException {
        int orientedWidth = specification.rotateClockwise ? sourceHeight : sourceWidth;
        int orientedHeight = specification.rotateClockwise ? sourceWidth : sourceHeight;
        double scale = Math.min(specification.cellWidth() / orientedWidth, specification.cellHeight() / orientedHeight);
        double width = orientedWidth * scale;
        double height = orientedHeight * scale;
        double x = cellX + (specification.cellWidth() - width) / 2;
        double y = cellY + (specification.cellHeight() - height) / 2;
        if (specification.rotateClockwise) {
            stream.drawImage(photo, new Matrix(0, -points(height), points(width), 0, points(x), points(y + height)));
        } else {
            stream.drawImage(photo, points(x), points(y), points(width), points(height));
        }
    }

    private static void drawCutLines(PDPageContentStream stream, Specification specification, double left, double bottom)
            throws IOException {
        double right = left + specification.columns * specification.cellWidth();
        double top = bottom + specification.rows * specification.cellHeight();
        stream.setStrokingColor(0f);
        stream.setLineWidth(0.25f);
        for (int column = 0; column <= specification.columns; column++) {
            float x = points(left + column * specification.cellWidth());
            stream.moveTo(x, points(bottom));
            stream.lineTo(x, points(top));
        }
        for (int row = 0; row <= specification.rows; row++) {
            float y = points(bottom + row * specification.cellHeight());
            stream.moveTo(points(left), y);
            stream.lineTo(points(right), y);
        }
        stream.stroke();
    }

    private static float points(double millimetres) {
        return (float) (millimetres * POINTS_PER_MM);
    }

    private static void setStableDocumentId(PDDocument document, BufferedImage image, int sourceWidth, int sourceHeight,
            Specification specification, int quality) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((RENDER_VERSION + ":" + specification.name()).getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(20).putInt(sourceWidth).putInt(sourceHeight)
                    .putInt(image.getWidth()).putInt(image.getHeight()).putInt(quality).array());
            int[] row = new int[image.getWidth()];
            ByteBuffer pixels = ByteBuffer.allocate(row.length * Integer.BYTES);
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                pixels.clear();
                for (int pixel : row) pixels.putInt(pixel);
                digest.update(pixels.array());
            }
            byte[] id = Arrays.copyOf(digest.digest(), 16);
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            document.getDocument().setDocumentID(ids);
            document.setDocumentId(1L);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override public void write(int value) throws IOException {
            checkSize(1);
            bytes.write(value);
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            checkSize(length);
            bytes.write(value, offset, length);
        }

        private void checkSize(int length) throws IOException {
            if (length > MAX_PDF_BYTES - bytes.size()) throw new PdfLimitExceeded();
        }

        byte[] toByteArray() { return bytes.toByteArray(); }
    }

    private static final class PdfLimitExceeded extends IOException { }
}
