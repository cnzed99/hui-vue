package cn.iocoder.yudao.server.print.miniappdemo.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded, single-disk, non-ZIP64 reader. Only a selected PDF is inflated. */
public final class StrictZipPdf {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20;
    private static final int UTF8_FLAG = 0x800;
    private static final Set<String> ARCHIVES = Set.of("zip", "rar", "7z", "tar", "gz");
    private final byte[] bytes;
    private final long deadline;
    private final List<Entry> entries;

    private StrictZipPdf(byte[] bytes, long deadline) {
        this.bytes = bytes;
        this.deadline = deadline;
        this.entries = readDirectory();
    }

    public static StrictZipPdf read(InputStream input, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (;;) {
            checkDeadline(deadline);
            int read = input.read(buffer);
            if (read < 0) break;
            require(read <= MAX_BYTES - output.size());
            output.write(buffer, 0, read);
        }
        checkDeadline(deadline);
        return new StrictZipPdf(output.toByteArray(), deadline);
    }

    public String sha256() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public List<Candidate> candidates() {
        return entries.stream().filter(Entry::pdf)
                .map(entry -> new Candidate(entry.ordinal, entry.displayName, entry.size)).toList();
    }

    public byte[] extract(int ordinal) {
        require(ordinal >= 0 && ordinal < entries.size());
        Entry entry = entries.get(ordinal);
        require(entry.pdf);
        checkDeadline(deadline);
        byte[] result = entry.method == 0
                ? java.util.Arrays.copyOfRange(bytes, entry.dataOffset, entry.dataOffset + entry.compressed)
                : inflate(entry);
        CRC32 crc = new CRC32();
        crc.update(result);
        require(result.length == entry.size && crc.getValue() == entry.crc);
        require(result.length >= 5 && result[0] == '%' && result[1] == 'P'
                && result[2] == 'D' && result[3] == 'F' && result[4] == '-');
        checkDeadline(deadline);
        return result;
    }

    private byte[] inflate(Entry entry) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(bytes, entry.dataOffset, entry.compressed);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                checkDeadline(deadline);
                int read = inflater.inflate(buffer);
                require((read > 0 || inflater.finished()) && read <= entry.size - output.size());
                output.write(buffer, 0, read);
            }
            require(inflater.getRemaining() == 0);
            return output.toByteArray();
        } catch (DataFormatException ex) {
            throw new InvalidArchive();
        } finally {
            inflater.end();
        }
    }

    private List<Entry> readDirectory() {
        int end = bytes.length - 22;
        int earliest = Math.max(0, end - 65535);
        while (end >= earliest && !(u32(end) == 0x06054b50 && end + 22 + u16(end + 20) == bytes.length)) {
            if ((end & 1023) == 0) checkDeadline(deadline);
            end--;
        }
        require(end >= earliest && u16(end + 4) == 0 && u16(end + 6) == 0);
        int count = u16(end + 10);
        require(count > 0 && count <= MAX_ENTRIES && u16(end + 8) == count);
        long directoryOffset = u32(end + 16);
        require(directoryOffset <= end && u32(end + 12) == end - directoryOffset);
        int directory = (int) directoryOffset;
        int cursor = directory;
        List<Entry> results = new ArrayList<>();
        Set<String> explicitNames = new HashSet<>();
        Map<String, String> normalizedPaths = new HashMap<>();
        Set<String> files = new HashSet<>();
        Set<String> folders = new HashSet<>();
        long total = 0;
        for (int ordinal = 0; ordinal < count; ordinal++) {
            checkDeadline(deadline);
            require(cursor <= end - 46 && u32(cursor) == 0x02014b50);
            int flags = u16(cursor + 8);
            int method = u16(cursor + 10);
            require((method == 0 || method == 8) && (flags & ~(UTF8_FLAG | (method == 8 ? 6 : 0))) == 0);
            long crc = u32(cursor + 16);
            long compressed = u32(cursor + 20);
            long size = u32(cursor + 24);
            int nameLength = u16(cursor + 28);
            int extraLength = u16(cursor + 30);
            int commentLength = u16(cursor + 32);
            int nameOffset = cursor + 46;
            require(nameLength > 0 && nameLength <= 1024 && nameOffset + nameLength + extraLength + commentLength <= end);
            require(u16(cursor + 34) == 0 && u16(cursor + 6) <= 20);
            require(compressed <= MAX_BYTES && size <= MAX_BYTES && size <= compressed * 100);
            require(method != 0 || size == compressed);
            total += size;
            require(total <= 10L * MAX_BYTES);
            validateExtra(nameOffset + nameLength, extraLength);
            String name = decodeName(nameOffset, nameLength, flags);
            boolean directoryEntry = name.endsWith("/");
            long attrs = u32(cursor + 38);
            int unixType = (int) ((attrs >>> 16) & 0170000);
            require(unixType == 0 || unixType == (directoryEntry ? 0040000 : 0100000));
            require((attrs & 0x10) == 0 || directoryEntry);
            require(!directoryEntry || size == 0);
            String leaf = validateName(name, directoryEntry, explicitNames, normalizedPaths, files, folders);
            String suffix = leaf.substring(leaf.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            require(leaf.lastIndexOf('.') < 0 || !ARCHIVES.contains(suffix));
            long localOffset = u32(cursor + 42);
            require(localOffset <= directory - 30);
            int local = (int) localOffset;
            require(u32(local) == 0x04034b50 && u16(local + 4) == u16(cursor + 6)
                    && u16(local + 6) == flags && u16(local + 8) == method
                    && u32(local + 14) == crc && u32(local + 18) == compressed && u32(local + 22) == size
                    && u16(local + 26) == nameLength);
            int localExtra = u16(local + 28);
            int data = local + 30 + nameLength + localExtra;
            require(data + compressed <= directory);
            require(java.util.Arrays.equals(bytes, local + 30, local + 30 + nameLength,
                    bytes, nameOffset, nameOffset + nameLength));
            validateExtra(local + 30 + nameLength, localExtra);
            boolean pdf = !directoryEntry && leaf.toLowerCase(Locale.ROOT).endsWith(".pdf");
            int displayLength = leaf.codePointCount(0, leaf.length());
            String display = displayLength <= 200 ? leaf : leaf.substring(0, leaf.offsetByCodePoints(0, 200));
            results.add(new Entry(ordinal, display, pdf, local, data, (int) compressed, (int) size, method, crc));
            cursor = nameOffset + nameLength + extraLength + commentLength;
        }
        require(cursor == end);
        int previousEnd = 0;
        for (Entry entry : results.stream().sorted(Comparator.comparingInt(Entry::localOffset)).toList()) {
            require(entry.localOffset == previousEnd);
            previousEnd = entry.dataOffset + entry.compressed;
        }
        require(previousEnd == directory);
        return List.copyOf(results);
    }

    private String decodeName(int offset, int length, int flags) {
        if ((flags & UTF8_FLAG) == 0) {
            for (int i = offset; i < offset + length; i++) require(bytes[i] >= 0);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException ex) {
            throw new InvalidArchive();
        }
    }

    private static String validateName(String name, boolean directory, Set<String> explicitNames,
            Map<String, String> normalizedPaths, Set<String> files, Set<String> folders) {
        String[] segments = (directory ? name.substring(0, name.length() - 1) : name).split("/", -1);
        StringBuilder originalPath = new StringBuilder();
        StringBuilder normalizedPath = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            String normalized = Normalizer.normalize(segment, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            require(safeSegment(segment) && safeSegment(normalized));
            if (i > 0) {
                originalPath.append('/');
                normalizedPath.append('/');
            }
            originalPath.append(segment);
            normalizedPath.append(normalized);
            String key = normalizedPath.toString();
            String previous = normalizedPaths.putIfAbsent(key, originalPath.toString());
            require(previous == null || previous.contentEquals(originalPath));
            if (i < segments.length - 1 || directory) {
                require(!files.contains(key));
                folders.add(key);
            } else {
                require(!folders.contains(key));
                files.add(key);
            }
        }
        require(explicitNames.add(normalizedPath.toString()));
        return segments[segments.length - 1];
    }

    private static boolean safeSegment(String name) {
        return !name.isBlank() && !name.equals(".") && !name.equals("..") && !name.endsWith(".")
                && name.equals(name.strip()) && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.indexOf(':') < 0 && name.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT);
    }

    private void validateExtra(int cursor, int length) {
        int end = cursor + length;
        while (cursor < end) {
            require(cursor <= end - 4);
            int type = u16(cursor);
            require(type != 0x0001 && type != 0x7075 && type != 0x9901);
            cursor += 4 + u16(cursor + 2);
            require(cursor <= end);
        }
    }

    private int u16(int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private long u32(int offset) {
        return u16(offset) | ((long) u16(offset + 2) << 16);
    }

    public static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw new DeadlineExceeded();
    }

    private static void require(boolean condition) {
        if (!condition) throw new InvalidArchive();
    }

    public record Candidate(int ordinal, String displayName, int declaredSizeBytes) { }

    private record Entry(int ordinal, String displayName, boolean pdf, int localOffset,
                         int dataOffset, int compressed, int size, int method, long crc) { }

    public static final class InvalidArchive extends RuntimeException { }

    public static final class DeadlineExceeded extends RuntimeException { }
}
