package cn.iocoder.yudao.server.print.miniappdemo.text;

import jakarta.annotation.PostConstruct;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextException.Reason.*;

/** A fixed plain-text format. All input-dependent work stays inside one synchronous permit. */
@Component
public class MiniappDemoTextPdfRenderer {
    private static final float FONT_SIZE = 12;
    private static final float MARGIN = 42;
    private static final float LEADING = 18;
    private static final int LINES_PER_PAGE = (int) Math.floor((PDRectangle.A4.getHeight() - 2 * MARGIN) / LEADING);
    private final MiniappDemoTextProperties properties;
    private Semaphore slots;
    private Path fontPath;

    public MiniappDemoTextPdfRenderer(MiniappDemoTextProperties properties) { this.properties = properties; }

    @PostConstruct
    public void start() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        Path configured = properties.getFontPath().normalize();
        if (Files.isSymbolicLink(configured) || !Files.isRegularFile(configured, LinkOption.NOFOLLOW_LINKS)
                || !configured.toRealPath().equals(configured)) throw new IOException("Unsafe text font asset");
        if (!properties.getFontSha256().equals(HexFormat.of().formatHex(hash(Files.readAllBytes(configured))))) {
            throw new IOException("Text font asset hash mismatch");
        }
        try (TrueTypeCollection collection = new TrueTypeCollection(configured.toFile())) {
            if (collection.getFontByName(properties.getFontName()) == null) throw new IOException("Text font name mismatch");
        }
        fontPath = configured;
        slots = new Semaphore(properties.getMaxConcurrentRenders(), true);
    }

    public String normalize(String raw) {
        requireEnabled();
        if (raw == null) throw failure(INVALID_INPUT);
        // Bound expansion before allocating a normalized copy; canonical count is checked below.
        if (raw.length() > properties.getMaxCodePoints() * 2) throw failure(INPUT_TOO_LARGE);
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        if (normalized.codePointCount(0, normalized.length()) > properties.getMaxCodePoints()
                || normalized.getBytes(StandardCharsets.UTF_8).length > properties.getMaxUtf8Bytes()) throw failure(INPUT_TOO_LARGE);
        boolean visible = false;
        for (int offset = 0; offset < normalized.length();) {
            int cp = normalized.codePointAt(offset);
            if (cp >= 0xd800 && cp <= 0xdfff || Character.isISOControl(cp) && cp != '\n') throw failure(INVALID_INPUT);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) visible = true;
            offset += Character.charCount(cp);
        }
        if (!visible) throw failure(INVALID_INPUT);
        return normalized;
    }

    public byte[] render(String normalized) {
        requireEnabled();
        if (!slots.tryAcquire()) throw failure(RENDER_BUSY);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getRenderTimeoutMillis());
        try {
            checkDeadline(deadline);
            try (TrueTypeCollection collection = new TrueTypeCollection(fontPath.toFile()); PDDocument document = new PDDocument()) {
                checkDeadline(deadline);
                var ttf = collection.getFontByName(properties.getFontName());
                if (ttf == null) throw failure(RENDER_UNAVAILABLE);
                PDType0Font font = PDType0Font.load(document, ttf, true);
                checkDeadline(deadline);
                List<String> lines = layout(normalized, font, deadline);
                byte[] id = Arrays.copyOf(hash((properties.getRenderVersion() + "\n" + properties.getFontSha256()
                        + "\n" + normalized).getBytes(StandardCharsets.UTF_8)), 16);
                COSArray ids = new COSArray(); ids.add(new COSString(id)); ids.add(new COSString(id));
                document.getDocument().setDocumentID(ids);
                document.setDocumentId(1L);
                PDDocumentInformation info = new PDDocumentInformation();
                info.setProducer(properties.getRenderVersion());
                GregorianCalendar date = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                date.clear(); date.set(2000, 0, 1, 0, 0, 0);
                info.setCreationDate(date); info.setModificationDate(date);
                document.setDocumentInformation(info);
                for (int first = 0; first < lines.size(); first += LINES_PER_PAGE) {
                    checkDeadline(deadline);
                    // layout has already rejected the first line requiring page 21.
                    PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText(); stream.setFont(font, FONT_SIZE); stream.setLeading(LEADING);
                        stream.newLineAtOffset(MARGIN, PDRectangle.A4.getHeight() - MARGIN - FONT_SIZE);
                        for (int i = first; i < Math.min(lines.size(), first + LINES_PER_PAGE); i++) {
                            checkDeadline(deadline);
                            if (!lines.get(i).isEmpty()) stream.showText(lines.get(i));
                            stream.newLine();
                            checkDeadline(deadline);
                        }
                        stream.endText();
                    }
                    checkDeadline(deadline);
                }
                try (BoundedOutput output = new BoundedOutput(properties.getMaxPdfBytes(), deadline)) {
                    checkDeadline(deadline);
                    document.save(output, CompressParameters.NO_COMPRESSION);
                    checkDeadline(deadline);
                    return output.toByteArray();
                }
            }
        } catch (MiniappDemoTextException failure) {
            throw failure;
        } catch (IOException | RuntimeException ignored) {
            throw failure(RENDER_UNAVAILABLE);
        } finally {
            // try-with-resources closes the document and TTC before releasing this permit.
            slots.release();
        }
    }

    private List<String> layout(String text, PDType0Font font, long deadline) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder(); float width = 0;
        float maximum = PDRectangle.A4.getWidth() - 2 * MARGIN;
        for (int offset = 0; offset < text.length();) {
            checkDeadline(deadline);
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            if (cp == '\n') { addLine(lines, line.toString(), deadline); line.setLength(0); width = 0; continue; }
            if (!font.hasGlyph(cp)) throw failure(UNSUPPORTED_CHARACTER);
            String glyph = new String(Character.toChars(cp));
            float glyphWidth = font.getStringWidth(glyph) * FONT_SIZE / 1000;
            checkDeadline(deadline);
            if (width + glyphWidth > maximum && !line.isEmpty()) {
                addLine(lines, line.toString(), deadline); line.setLength(0); width = 0;
            }
            line.append(glyph); width += glyphWidth;
        }
        addLine(lines, line.toString(), deadline);
        return lines;
    }

    private void addLine(List<String> lines, String value, long deadline) {
        checkDeadline(deadline);
        if (lines.size() >= LINES_PER_PAGE * properties.getMaxPages()) throw failure(PAGE_LIMIT);
        lines.add(value);
    }

    private void requireEnabled() { if (!properties.isEnabled() || fontPath == null || slots == null) throw failure(DISABLED); }
    static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw failure(RENDER_TIMEOUT);
    }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException ignored) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static MiniappDemoTextException failure(MiniappDemoTextException.Reason reason) { return new MiniappDemoTextException(reason); }

    static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum; private final long deadline;
        BoundedOutput(int maximum, long deadline) { this.maximum = maximum; this.deadline = deadline; }
        @Override public void write(int value) { check(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) { check(length); bytes.write(value, offset, length); }
        private void check(int length) {
            checkDeadline(deadline);
            if (length < 0 || bytes.size() > maximum - length) throw failure(PDF_TOO_LARGE);
        }
        byte[] toByteArray() { return bytes.toByteArray(); }
        @Override public void close() throws IOException { bytes.close(); }
    }
}
