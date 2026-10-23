package cn.iocoder.yudao.server.print.file;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict PDF parsing, not extension inference. Hard process isolation remains a later gate. */
public final class PdfInspection {
    private PdfInspection() {}
    public static int inspect(Path file, Path scratchDirectory, long scratchBytes, int maxPages) throws Failure {
        try {
            try (var input = Files.newInputStream(file)) {
                if (!"%PDF-".equals(new String(input.readNBytes(5), StandardCharsets.US_ASCII))) {
                    throw new Failure("PDF_INVALID");
                }
            }
            try (RandomAccessReadBufferedFile input = new RandomAccessReadBufferedFile(file.toFile())) {
                PDFParser parser = new PDFParser(input, "", null, null,
                        () -> new ScratchFile(MemoryUsageSetting.setupTempFileOnly(scratchBytes)
                                .setTempDir(scratchDirectory.toFile())));
                try (PDDocument document = parser.parse(false)) {
                    if (document.isEncrypted()) throw new Failure("PDF_ENCRYPTED");
                    int pages = 0;
                    for (PDPage ignored : document.getPages()) {
                        if (++pages > maxPages) throw new Failure("PDF_PAGE_LIMIT");
                    }
                    if (pages <= 0 || document.getNumberOfPages() != pages) throw new Failure("PDF_INVALID");
                    return pages;
                }
            }
        } catch (InvalidPasswordException ex) {
            throw new Failure("PDF_ENCRYPTED");
        } catch (IOException | RuntimeException ex) {
            // Never expose parser exception messages, document content or server paths.
            throw new Failure("PDF_INVALID");
        }
    }
    public static final class Failure extends Exception {
        private final String code;
        public Failure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
