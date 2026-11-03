package cn.iocoder.yudao.server.print.miniappdemo.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class StrictZipPdfChecks {
    private static final byte[] PDF = "%PDF-1.4\nfixture\n".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        byte[] valid = zip("note.txt", "print.pdf");
        StrictZipPdf inspected = read(valid);
        check(inspected.candidates().size() == 1 && inspected.candidates().get(0).ordinal() == 1, "directory ordinal");
        check(Arrays.equals(inspected.extract(1), PDF), "extracted PDF");
        check(inspected.sha256().matches("[0-9a-f]{64}"), "content digest");
        reject(() -> inspected.extract(0), "non-PDF");
        reject(() -> read(zip("../escape.pdf")), "traversal");
        reject(() -> read(zip("A.pdf", "a.pdf")), "case collision");
        reject(() -> read(zip("a/one.pdf", "A/two.pdf")), "directory alias");
        reject(() -> read(zip("hidden.ZIP")), "nested archive name");
        reject(() -> read(Arrays.copyOf(valid, valid.length - 1)), "truncated end record");
        int central = centralOffset(valid);
        byte[] badMethod = valid.clone();
        badMethod[central + 10] = 12;
        reject(() -> read(badMethod), "unsupported method");
        byte[] encrypted = valid.clone();
        encrypted[central + 8] |= 1;
        reject(() -> read(encrypted), "encrypted");
        byte[] descriptor = valid.clone();
        descriptor[central + 8] |= 8;
        reject(() -> read(descriptor), "data descriptor");
        byte[] badVersion = valid.clone();
        badVersion[central + 6] = 45;
        reject(() -> read(badVersion), "ZIP64 version");
        byte[] symlink = valid.clone();
        symlink[central + 41] = (byte) 0xa0;
        reject(() -> read(symlink), "symlink mode");
        byte[] localMismatch = valid.clone();
        localMismatch[30] = 'N';
        reject(() -> read(localMismatch), "local/central name mismatch");
        byte[] badCrc = zip("print.pdf");
        badCrc[30 + "print.pdf".length() + 7] ^= 1;
        reject(() -> read(badCrc).extract(0), "actual payload CRC");
        String[] tooMany = new String[21];
        for (int i = 0; i < tooMany.length; i++) tooMany[i] = i + ".pdf";
        reject(() -> read(zip(tooMany)), "entry bound");
        try {
            StrictZipPdf.read(new ByteArrayInputStream(valid), System.nanoTime() - 1);
            throw new AssertionError("expired deadline accepted");
        } catch (StrictZipPdf.DeadlineExceeded expected) {
            // Expected.
        }
        System.out.println("ZIP_PARSER_CHECKS_PASS (16 checks)");
    }

    private static int centralOffset(byte[] archive) {
        int offset = archive.length - 22 + 16;
        return (archive[offset] & 255) | ((archive[offset + 1] & 255) << 8);
    }

    private static StrictZipPdf read(byte[] bytes) throws Exception {
        return StrictZipPdf.read(new ByteArrayInputStream(bytes), System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    private static byte[] zip(String... names) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(PDF.length);
                entry.setCompressedSize(PDF.length);
                CRC32 crc = new CRC32();
                crc.update(PDF);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(PDF);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static void reject(Checked action, String label) throws Exception {
        try {
            action.run();
            throw new AssertionError("Unexpected acceptance: " + label);
        } catch (StrictZipPdf.InvalidArchive expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }
}
