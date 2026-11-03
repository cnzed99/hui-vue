package cn.iocoder.yudao.server.print.miniappdemo.image;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.fileupload.FileItemHeaders;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class MiniappDemoIdPhotoMultipartRequest extends DefaultMultipartHttpServletRequest {
    private static final int MAX_PART_HEADERS = 1024;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final MultipartFile image;
    private final String specification;
    private final Path task;

    private MiniappDemoIdPhotoMultipartRequest(HttpServletRequest request, MultipartFile image,
            String specification, Path task) {
        super(request, files(image), Map.of(), Map.of());
        this.image = image; this.specification = specification; this.task = task;
    }

    static MiniappDemoIdPhotoMultipartRequest parse(HttpServletRequest request, Path root, long maximum)
            throws IOException, ServletException {
        String boundary = boundary(request.getContentType());
        Path task = Files.createTempDirectory(root, "task-");
        Files.setPosixFilePermissions(task, PRIVATE_DIRECTORY);
        try {
            byte[] boundaryBytes = boundary.getBytes(StandardCharsets.US_ASCII);
            byte[] first = ("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
            PushbackInputStream input = new PushbackInputStream(request.getInputStream(), first.length);
            byte[] actualFirst = input.readNBytes(first.length);
            if (!Arrays.equals(first, actualFirst)) throw invalid();
            input.unread(actualFirst);
            MultipartStream stream = new MultipartStream(input, boundaryBytes, null);
            stream.setPartHeaderSizeMax(MAX_PART_HEADERS);
            stream.setHeaderEncoding(StandardCharsets.UTF_8.name());
            if (!stream.skipPreamble()) throw invalid();
            var headerParser = new FileUpload();
            var seen = new HashSet<String>();
            StoredFile file = null;
            String specification = null;
            do {
                if (seen.size() == 2) throw invalid();
                FileItemHeaders headers = headerParser.getParsedHeaders(stream.readHeaders());
                String contentDisposition = singleHeader(headers, "Content-Disposition");
                if (contentDisposition == null || headers.getHeader("Content-Transfer-Encoding") != null) throw invalid();
                ContentDisposition disposition = ContentDisposition.parse(contentDisposition);
                String name = disposition.getName();
                String filename = disposition.getFilename();
                if (name == null || !("file".equals(name) || "specification".equals(name)) || !seen.add(name)
                        || !"form-data".equalsIgnoreCase(disposition.getType())
                        || "file".equals(name) != (filename != null && !filename.isEmpty())) throw invalid();
                String type = singleHeader(headers, "Content-Type");
                if (type != null && "multipart".equalsIgnoreCase(MediaType.parseMediaType(type).getType())) throw invalid();
                if ("file".equals(name)) {
                    if (type == null || !(MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(type)
                            || MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(type))) throw new UnsupportedPart();
                    Path path = task.resolve("input.bin");
                    long size = copyBounded(stream.newInputStream(), path, maximum);
                    file = new StoredFile(filename, type, path, size);
                } else {
                    byte[] value = stream.newInputStream().readNBytes(17);
                    if (value.length < 1 || value.length > 16) throw invalid();
                    specification = new String(value, StandardCharsets.US_ASCII);
                    if (!specification.matches("ONE_INCH|TWO_INCH|THREE_INCH")) throw invalid();
                }
            } while (stream.readBoundary());
            if (!seen.equals(Set.of("file", "specification")) || file == null || specification == null) throw invalid();
            return new MiniappDemoIdPhotoMultipartRequest(request, file, specification, task);
        } catch (IOException | ServletException | RuntimeException failure) {
            deleteTaskDirectory(task, root);
            throw failure;
        }
    }

    MultipartFile image() { return image; }
    String specification() { return specification; }
    void cleanup(Path root) { deleteTaskDirectory(task, root); }

    static String boundary(String contentType) {
        MediaType type = MediaType.parseMediaType(contentType);
        if (!MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(type)) throw new IllegalArgumentException();
        String boundary = type.getParameter("boundary");
        if (boundary != null && boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        if (boundary == null || !boundary.matches("[A-Za-z0-9'()+_,./:=?-]{1,70}")) {
            throw new IllegalArgumentException("Unsupported multipart boundary");
        }
        return boundary;
    }

    private static LinkedMultiValueMap<String, MultipartFile> files(MultipartFile image) {
        var values = new LinkedMultiValueMap<String, MultipartFile>();
        values.add("file", image);
        return values;
    }

    private static long copyBounded(InputStream input, Path target, long maximum) throws IOException, ServletException {
        long count = 0;
        try (input; OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(target, PRIVATE_FILE);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (count > maximum - read) throw new PartTooLarge();
                count += read; output.write(buffer, 0, read);
            }
        }
        if (count == 0) throw invalid();
        try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
        return count;
    }

    private static String singleHeader(FileItemHeaders headers, String name) throws ServletException {
        var values = headers.getHeaders(name);
        if (!values.hasNext()) return null;
        String first = values.next();
        if (values.hasNext()) throw invalid();
        return first;
    }

    @Override public String getParameter(String name) { return null; }
    @Override public String[] getParameterValues(String name) { return null; }
    @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
    @Override public Map<String, String[]> getParameterMap() { return Map.of(); }

    private static ServletException invalid() { return new ServletException("Invalid id photo multipart body"); }

    static void deleteTaskDirectory(Path task, Path root) {
        try {
            Path realRoot = root.toRealPath();
            Path candidate = task.toAbsolutePath().normalize();
            if (!candidate.getParent().equals(realRoot) || !candidate.getFileName().toString().startsWith("task-")
                    || Files.isSymbolicLink(candidate)) return;
            Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure; Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) { } catch (IOException ignored) { }
    }

    static final class PartTooLarge extends IOException { }
    static final class UnsupportedPart extends IOException { }

    private record StoredFile(String originalFilename, String contentType, Path path, long size) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return size == 0; }
        @Override public long getSize() { return size; }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException("Private multipart file"); }
    }
}
