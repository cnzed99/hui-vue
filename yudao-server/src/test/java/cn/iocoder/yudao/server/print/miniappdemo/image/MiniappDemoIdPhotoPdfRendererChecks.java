package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoPdfRenderer.Specification.*;

public final class MiniappDemoIdPhotoPdfRendererChecks {
    private static final double MM_PER_POINT = 25.4 / 72;
    private static final double ERROR_MM = 0.001;

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory(Path.of(args[0]), "id-photo-six-inch-");
        try {
            verify(scratch, ONE_INCH, 25, 35, 5, 2, 13.5, 16, false);
            verify(scratch, TWO_INCH, 35, 49, 2, 2, 27, 16, true);
            verify(scratch, THREE_INCH, 55, 84, 2, 1, 21, 9, false);
            byte[] fitted = MiniappDemoIdPhotoPdfRenderer.render(image(100, 100), ONE_INCH);
            try (var pdf = Loader.loadPDF(fitted)) {
                var matrix = operands(pdf.getPage(0), "cm").get(0);
                expectMm(matrix[0], 25);
                expectMm(matrix[3], 25);
                expectMm(matrix[4], 13.5);
                expectMm(matrix[5], 21);
            }
            try {
                MiniappDemoIdPhotoPdfRenderer.render(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), ONE_INCH);
                throw new AssertionError("alpha photo accepted");
            } catch (IllegalArgumentException expected) {
                // Input contract requires opaque RGB.
            }
            System.out.println("ID_PHOTO_SIX_INCH_OFFLINE_PASS");
        } finally {
            for (var specification : MiniappDemoIdPhotoPdfRenderer.Specification.values()) {
                Files.deleteIfExists(scratch.resolve(specification.name() + ".pdf"));
            }
            Files.delete(scratch);
        }
    }

    private static void verify(Path scratch, MiniappDemoIdPhotoPdfRenderer.Specification specification,
            int photoWidth, int photoHeight, int columns, int rows, double left, double bottom, boolean rotated) throws Exception {
        var picture = image(photoWidth * 4, photoHeight * 4);
        byte[] rendered = MiniappDemoIdPhotoPdfRenderer.render(picture, specification);
        check(Arrays.equals(rendered, MiniappDemoIdPhotoPdfRenderer.render(picture, specification)), "deterministic PDF bytes");
        Path sample = scratch.resolve(specification.name() + ".pdf");
        Files.write(sample, rendered);
        double cellWidth = rotated ? photoHeight : photoWidth;
        double cellHeight = rotated ? photoWidth : photoHeight;
        try (var pdf = Loader.loadPDF(Files.readAllBytes(sample))) {
            check(pdf.getNumberOfPages() == 1, "one page");
            var page = pdf.getPage(0);
            check(page.getRotation() == 0 && page.getCOSObject().containsKey(COSName.ROTATE), "explicit Rotate 0");
            expectMm(page.getMediaBox().getWidth(), 152);
            expectMm(page.getMediaBox().getHeight(), 102);
            check(!page.getResources().getFontNames().iterator().hasNext(), "no fonts");
            check(count(page.getResources().getXObjectNames()) == 1, "single image resource");
            var placements = operands(page, "cm");
            check(placements.size() == columns * rows && operands(page, "Do").size() == columns * rows, "image count");
            int index = 0;
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    double x = left + column * cellWidth;
                    double y = bottom + row * cellHeight;
                    double[] actual = placements.get(index++);
                    double[] expected = rotated
                            ? new double[] {0, -cellHeight, cellWidth, 0, x, y + cellHeight}
                            : new double[] {cellWidth, 0, 0, cellHeight, x, y};
                    for (int item = 0; item < 6; item++) expectMm(actual[item], expected[item]);
                    check(x >= 0 && y >= 0 && x + cellWidth <= 152 && y + cellHeight <= 102, "image within sheet");
                }
            }
            var starts = operands(page, "m");
            var ends = operands(page, "l");
            check(starts.size() == columns + rows + 2 && ends.size() == starts.size()
                    && operands(page, "S").size() == 1, "one stroke per shared boundary");
            for (int column = 0; column <= columns; column++) {
                double x = left + column * cellWidth;
                expectLine(starts, ends, column, x, bottom, x, bottom + rows * cellHeight);
            }
            for (int row = 0; row <= rows; row++) {
                double y = bottom + row * cellHeight;
                expectLine(starts, ends, columns + 1 + row, left, y, left + columns * cellWidth, y);
            }
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rendered));
        System.out.printf("%s %dx%d media=152x102mm margin=%s/%s sha256=%s%n", specification, columns, rows, left, bottom, sha);
    }

    private static List<double[]> operands(org.apache.pdfbox.pdmodel.PDPage page, String operation) throws Exception {
        List<double[]> matches = new ArrayList<>();
        List<Double> operands = new ArrayList<>();
        for (Object token : new PDFStreamParser(page).parse()) {
            if (token instanceof COSNumber number) operands.add((double) number.floatValue());
            if (token instanceof Operator operator) {
                if (operator.getName().equals(operation)) matches.add(operands.stream().mapToDouble(Double::doubleValue).toArray());
                operands.clear();
            }
        }
        return matches;
    }

    private static void expectLine(List<double[]> starts, List<double[]> ends, int index,
            double x0, double y0, double x1, double y1) {
        expectMm(starts.get(index)[0], x0);
        expectMm(starts.get(index)[1], y0);
        expectMm(ends.get(index)[0], x1);
        expectMm(ends.get(index)[1], y1);
    }

    private static BufferedImage image(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width / 3, height / 5);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static int count(Iterable<?> values) {
        int count = 0;
        for (Object ignored : values) count++;
        return count;
    }

    private static void expectMm(double points, double expected) {
        check(Math.abs(points * MM_PER_POINT - expected) <= ERROR_MM,
                "mm mismatch expected=" + expected + " actual=" + points * MM_PER_POINT);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
