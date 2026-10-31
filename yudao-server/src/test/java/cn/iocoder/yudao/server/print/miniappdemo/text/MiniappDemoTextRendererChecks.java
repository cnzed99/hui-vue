package cn.iocoder.yudao.server.print.miniappdemo.text;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Standalone test-only renderer checks; uses synthetic content and never reads business data. */
public final class MiniappDemoTextRendererChecks {
    private static int passed;
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected font path and optional fingerprint flag");
        MiniappDemoTextProperties p = properties(Path.of(args[0]));
        MiniappDemoTextPdfRenderer renderer = new MiniappDemoTextPdfRenderer(p); renderer.start();
        String content = renderer.normalize("打印测试 Print 123\r\n第二行\tAB");
        byte[] pdf = renderer.render(content);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        if (args.length == 2 && "--fingerprint".equals(args[1])) {
            System.out.println("{\"sha256\":\"" + digest + "\",\"bytes\":" + pdf.length + "}"); return;
        }
        check("same_input_same_bytes", Arrays.equals(pdf, renderer.render(content)));
        check("normalized_variants_same_bytes", Arrays.equals(pdf, renderer.render(renderer.normalize("打印测试 Print 123\n第二行    AB"))));
        try (var document = Loader.loadPDF(pdf)) {
            String extracted = new PDFTextStripper().getText(document);
            check("chinese_ascii_text_extracts", extracted.contains("打印测试 Print 123") && extracted.contains("第二行    AB"));
            check("single_page", document.getNumberOfPages() == 1);
        }
        try (var document = Loader.loadPDF(renderer.render(renderer.normalize("a\n".repeat(839) + "a")))) {
            check("twenty_pages_accepted", document.getNumberOfPages() == 20);
        }
        try (var document = Loader.loadPDF(renderer.render(renderer.normalize("长行文字".repeat(1000))))) {
            check("long_line_wraps", document.getNumberOfPages() > 1);
        }
        rejects("blank_rejected", MiniappDemoTextException.Reason.INVALID_INPUT, () -> renderer.normalize(" \n\t"));
        rejects("control_rejected", MiniappDemoTextException.Reason.INVALID_INPUT, () -> renderer.normalize("a\u0000b"));
        rejects("surrogate_rejected", MiniappDemoTextException.Reason.INVALID_INPUT, () -> renderer.normalize("a\ud800"));
        rejects("input_limit", MiniappDemoTextException.Reason.INPUT_TOO_LARGE, () -> renderer.normalize("a".repeat(10001)));
        rejects("unsupported_glyph", MiniappDemoTextException.Reason.UNSUPPORTED_CHARACTER, () -> renderer.render(renderer.normalize("a\uD83E\uDE80")));
        rejects("page_limit_before_render", MiniappDemoTextException.Reason.PAGE_LIMIT, () -> renderer.render(renderer.normalize("a\n".repeat(1000))));
        p.setMaxPdfBytes(16);
        rejects("bounded_pdf_output", MiniappDemoTextException.Reason.PDF_TOO_LARGE, () -> renderer.render(content));
        p.setMaxPdfBytes(2097152);
        p.setRenderTimeoutMillis(1);
        rejects("deadline_releases_resources", MiniappDemoTextException.Reason.RENDER_TIMEOUT, () -> renderer.render(content));
        p.setRenderTimeoutMillis(5000);
        check("next_render_after_failure", Arrays.equals(pdf, renderer.render(content)));
        Thread.currentThread().interrupt();
        rejects("interrupt_releases_resources", MiniappDemoTextException.Reason.RENDER_TIMEOUT, () -> renderer.render(content));
        Thread.interrupted();
        check("next_render_after_interrupt", Arrays.equals(pdf, renderer.render(content)));
        System.out.println("{\"status\":\"PASS\",\"passed\":" + passed + ",\"sha256\":\"" + digest + "\",\"bytes\":" + pdf.length + "}");
    }
    private static MiniappDemoTextProperties properties(Path font) {
        var p = new MiniappDemoTextProperties(); p.setEnabled(true); p.setFontPath(font);
        p.setFontSha256(MiniappDemoTextProperties.FONT_HASH); p.setFontName(MiniappDemoTextProperties.FONT_NAME);
        p.setRenderVersion(MiniappDemoTextProperties.RENDER_VERSION); p.setMaxCodePoints(10000); p.setMaxUtf8Bytes(61440);
        p.setMaxConcurrentRenders(1); p.setMaxPages(20); p.setMaxPdfBytes(2097152); p.setRenderTimeoutMillis(5000); return p;
    }
    private static void check(String name, boolean condition) { if (!condition) throw new AssertionError(name); passed++; }
    private static void rejects(String name, MiniappDemoTextException.Reason reason, Runnable action) {
        try { action.run(); throw new AssertionError(name); }
        catch (MiniappDemoTextException failure) { check(name, failure.reason() == reason); }
    }
}
