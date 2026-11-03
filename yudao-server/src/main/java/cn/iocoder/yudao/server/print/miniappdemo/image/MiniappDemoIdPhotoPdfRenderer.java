package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.util.Matrix;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        if (image.getColorModel().hasAlpha() || image.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_RGB) {
            throw new IllegalArgumentException("An opaque RGB photo is required");
        }
        double cellWidth = specification.cellWidth();
        double cellHeight = specification.cellHeight();
        double left = (PAGE_WIDTH_MM - cellWidth * specification.columns) / 2;
        double bottom = (PAGE_HEIGHT_MM - cellHeight * specification.rows) / 2;

        try (PDDocument document = new PDDocument()) {
            setStableDocumentId(document, image, specification);
            PDPage page = new PDPage(new PDRectangle(points(PAGE_WIDTH_MM), points(PAGE_HEIGHT_MM)));
            page.setRotation(0);
            document.addPage(page);
            PDImageXObject photo = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                for (int row = 0; row < specification.rows; row++) {
                    for (int column = 0; column < specification.columns; column++) {
                        drawPhoto(stream, photo, specification, left + column * cellWidth, bottom + row * cellHeight);
                    }
                }
                drawCutLines(stream, specification, left, bottom);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output, CompressParameters.NO_COMPRESSION);
            return output.toByteArray();
        }
    }

    private static void drawPhoto(PDPageContentStream stream, PDImageXObject photo, Specification specification,
            double cellX, double cellY) throws IOException {
        int orientedWidth = specification.rotateClockwise ? photo.getHeight() : photo.getWidth();
        int orientedHeight = specification.rotateClockwise ? photo.getWidth() : photo.getHeight();
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

    private static void setStableDocumentId(PDDocument document, BufferedImage image, Specification specification) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("id-photo-6in-v1:" + specification.name()).getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
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
}
