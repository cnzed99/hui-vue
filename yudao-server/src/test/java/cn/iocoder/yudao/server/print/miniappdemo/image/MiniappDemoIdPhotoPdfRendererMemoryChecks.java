package cn.iocoder.yudao.server.print.miniappdemo.image;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
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
            byte[] pdf = render(image, Specification.ONE_INCH);
            if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-') {
                throw new AssertionError("Invalid PDF output");
            }
            sampledPeak.accumulateAndGet(heap.getHeapMemoryUsage().getUsed(), Math::max);
            System.out.printf("ID_PHOTO_HEAP_BOUND_PASS width=%d height=%d pixels=%d layout=ONE_INCH pdf_bytes=%d sampled_peak_heap_bytes=%d max_heap_bytes=%d%n",
                    image.getWidth(), image.getHeight(), MAX_PIXELS, pdf.length, sampledPeak.get(), Runtime.getRuntime().maxMemory());
        } finally {
            finished.set(true);
            sampler.join();
        }
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
