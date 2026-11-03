package cn.iocoder.yudao.server.print.miniappdemo.archive;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.apache.tomcat.util.http.fileupload.FileItemHeaders;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Archive-only multipart buffering. The native container's disk-backed multipart parser is bypassed. */
final class ArchiveMultipartRequest extends DefaultMultipartHttpServletRequest {
    private static final int MAX_PART_HEADERS = 1024;
    private final List<Part> parts;

    private ArchiveMultipartRequest(HttpServletRequest request, List<Part> parts) {
        super(request, new LinkedMultiValueMap<>(), Map.of(), Map.of());
        this.parts = List.copyOf(parts);
    }

    static ArchiveMultipartRequest parse(HttpServletRequest request, boolean select) throws IOException, ServletException {
        String boundary = boundary(request.getContentType());
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
        var parts = new ArrayList<Part>(select ? 3 : 1);
        Set<String> required = select ? Set.of("archive", "archiveSha256", "ordinal") : Set.of("archive");
        do {
            if (parts.size() == required.size()) throw invalid();
            FileItemHeaders headers = headerParser.getParsedHeaders(stream.readHeaders());
            String contentDisposition = singleHeader(headers, "Content-Disposition");
            if (contentDisposition == null || headers.getHeader("Content-Transfer-Encoding") != null) throw invalid();
            ContentDisposition disposition = ContentDisposition.parse(contentDisposition);
            String name = disposition.getName();
            String filename = disposition.getFilename();
            if (name == null || !required.contains(name) || !seen.add(name)
                    || !"form-data".equalsIgnoreCase(disposition.getType())
                    || "archive".equals(name) != (filename != null && !filename.isEmpty())) throw invalid();
            String type = singleHeader(headers, "Content-Type");
            if (type != null && "multipart".equalsIgnoreCase(MediaType.parseMediaType(type).getType())) throw invalid();
            int max = switch (name) {
                case "archive" -> StrictZipPdf.MAX_BYTES;
                case "archiveSha256" -> 64;
                case "ordinal" -> 2;
                default -> throw invalid();
            };
            byte[] bytes = stream.newInputStream().readNBytes(max + 1);
            if (bytes.length > max) throw new PartTooLarge();
            parts.add(new MemoryPart(name, filename, type, bytes));
        } while (stream.readBoundary());
        if (!seen.equals(required)) throw invalid();
        return new ArchiveMultipartRequest(request, parts);
    }

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

    private static String singleHeader(FileItemHeaders headers, String name) throws ServletException {
        var values = headers.getHeaders(name);
        if (!values.hasNext()) return null;
        String first = values.next();
        if (values.hasNext()) throw invalid();
        return first;
    }

    @Override
    public Collection<Part> getParts() { return parts; }

    @Override
    public Part getPart(String name) {
        return parts.stream().filter(part -> part.getName().equals(name)).findFirst().orElse(null);
    }

    // No request parameters are used by these routes. Delegating any of these to the
    // native request can activate Tomcat's disk-backed multipart parser.
    @Override
    public String getParameter(String name) { return null; }

    @Override
    public String[] getParameterValues(String name) { return null; }

    @Override
    public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }

    @Override
    public Map<String, String[]> getParameterMap() { return Map.of(); }

    private static ServletException invalid() { return new ServletException("Invalid archive multipart body"); }

    static final class PartTooLarge extends IOException { }

    private record MemoryPart(String name, String filename, String contentType, byte[] bytes) implements Part {
        @Override
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override
        public String getContentType() { return contentType; }
        @Override
        public String getName() { return name; }
        @Override
        public String getSubmittedFileName() { return filename; }
        @Override
        public long getSize() { return bytes.length; }
        @Override
        public void write(String fileName) { throw new UnsupportedOperationException(); }
        @Override
        public void delete() { }
        @Override
        public String getHeader(String name) { return "Content-Type".equalsIgnoreCase(name) ? contentType : null; }
        @Override
        public Collection<String> getHeaders(String name) {
            String value = getHeader(name);
            return value == null ? List.of() : List.of(value);
        }
        @Override
        public Collection<String> getHeaderNames() { return contentType == null ? List.of() : List.of("Content-Type"); }
    }
}
