package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoPdfRenderer.*;

public final class MiniappDemoIdPhotoPdfRendererMemoryChecks {
    public static void main(String[] args) throws Exception {
        if (Runtime.getRuntime().maxMemory() != 192L * 1024 * 1024) throw new AssertionError("Expected -Xmx192m");
        expectRejected(MAX_WIDTH + 1, 1, "width");
        expectRejected(1, MAX_HEIGHT + 1, "height");
        expectRejected(MAX_WIDTH, MAX_HEIGHT + 1, "pixel product");
        expectRejected(0, 1, "empty");
        AtomicBoolean finished = new AtomicBoolean();
        AtomicLong sampledPeak = new AtomicLong();
        var heap = ManagementFactory.getMemoryMXBean();
        Thread sampler = new Thread(() -> {
            while (!finished.get()) {
                sampledPeak.accumulateAndGet(heap.getHeapMemoryUsage().getUsed(), Math::max);
                java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            }
        }, "heap-sample");
        sampler.start();
        try {
            var image = new BufferedImage(MAX_WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            int seed = 0x74c12a15;
            for (int i = 0; i < pixels.length; i++) {
                seed ^= seed << 13;
                seed ^= seed >>> 17;
                seed ^= seed << 5;
                pixels[i] = seed & 0x00ffffff;
            }
            verify(image, Specification.ONE_INCH, 25, 35);
            verify(image, Specification.TWO_INCH, 35, 49);
            verify(image, Specification.THREE_INCH, 55, 84);
            var lowResolution = new BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB);
            try (var document = Loader.loadPDF(render(lowResolution, Specification.ONE_INCH))) {
                var photo = imageResource(document.getPage(0));
                if (photo.getWidth() != 80 || photo.getHeight() != 120) throw new AssertionError("low resolution upscaled");
            }
            sampledPeak.accumulateAndGet(heap.getHeapMemoryUsage().getUsed(), Math::max);
            System.out.printf("ID_PHOTO_HEAP_BOUND_PASS width=%d height=%d pixels=%d sampled_peak_heap_bytes=%d max_heap_bytes=%d%n",
                    image.getWidth(), image.getHeight(), MAX_PIXELS, sampledPeak.get(), Runtime.getRuntime().maxMemory());
        } finally {
            finished.set(true);
            sampler.join();
        }
    }

    private static void verify(BufferedImage input, Specification specification, int widthMm, int heightMm) throws Exception {
        byte[] pdf = render(input, specification);
        if (pdf.length >= MAX_PDF_BYTES || !Arrays.equals(pdf, render(input, specification))) {
            throw new AssertionError("Output limit or determinism failed for " + specification);
        }
        try (var document = Loader.loadPDF(pdf)) {
            var photo = imageResource(document.getPage(0));
            if (!COSName.DCT_DECODE.equals(photo.getCOSObject().getDictionaryObject(COSName.FILTER))) {
                throw new AssertionError("Embedded image is not JPEG");
            }
            double millimetresPerSourcePixel = Math.min((double) widthMm / input.getWidth(), (double) heightMm / input.getHeight());
            double dpi = Math.min(photo.getWidth() * 25.4 / (input.getWidth() * millimetresPerSourcePixel),
                    photo.getHeight() * 25.4 / (input.getHeight() * millimetresPerSourcePixel));
            if (dpi < 300 || photo.getWidth() > Math.ceil(widthMm * 300 / 25.4)
                    || photo.getHeight() > Math.ceil(heightMm * 300 / 25.4)) throw new AssertionError("Unexpected DPI");
            String producer = document.getDocumentInformation().getProducer();
            if (!producer.matches("id-photo-6in-v2/jpeg-(92|88|84)/" + photo.getWidth() + "x" + photo.getHeight())) {
                throw new AssertionError("Missing encoder configuration");
            }
            System.out.printf("ID_PHOTO_JPEG_PASS spec=%s image=%dx%d effective_dpi=%.2f pdf_bytes=%d producer=%s%n",
                    specification, photo.getWidth(), photo.getHeight(), dpi, pdf.length, producer);
        }
    }

    private static PDImageXObject imageResource(org.apache.pdfbox.pdmodel.PDPage page) throws Exception {
        var resources = page.getResources();
        var names = resources.getXObjectNames().iterator();
        if (!names.hasNext() || !(resources.getXObject(names.next()) instanceof PDImageXObject image) || names.hasNext()) {
            throw new AssertionError("Expected one shared image XObject");
        }
        return image;
    }

    private static void expectRejected(int width, int height, String label) throws Exception {
        var guarded = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB) {
            @Override public int getWidth() { return width; }
            @Override public int getHeight() { return height; }
            @Override public int[] getRGB(int x, int y, int w, int h, int[] values, int offset, int stride) {
                throw new AssertionError("Read pixels for invalid " + label);
            }
        };
        try {
            render(guarded, Specification.TWO_INCH);
            throw new AssertionError("Accepted invalid " + label);
        } catch (IllegalArgumentException expected) {
            if (!INPUT_LIMIT_MESSAGE.equals(expected.getMessage())) throw expected;
        }
        System.out.println("ID_PHOTO_REJECT_BEFORE_ENCODING " + label);
    }
}
