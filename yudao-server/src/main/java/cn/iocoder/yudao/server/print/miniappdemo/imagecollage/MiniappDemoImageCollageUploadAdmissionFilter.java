package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class MiniappDemoImageCollageUploadAdmissionFilter extends OncePerRequestFilter {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private final MiniappDemoImageCollageProperties properties;
    private final WebProperties webProperties;
    private final MiniappDemoCollageUploadProperties staged;
    private Semaphore uploads;
    private Path multipartRoot;
    private String targetRequestPath;

    public MiniappDemoImageCollageUploadAdmissionFilter(MiniappDemoImageCollageProperties properties,
            WebProperties webProperties) {
        this(properties, webProperties, new MiniappDemoCollageUploadProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MiniappDemoImageCollageUploadAdmissionFilter(MiniappDemoImageCollageProperties properties,
            WebProperties webProperties, MiniappDemoCollageUploadProperties staged) {
        this.properties = properties;
        this.webProperties = webProperties;
        this.staged = staged;
    }

    @PostConstruct
    public void initialize() {
        properties.validateEnabled();
        targetRequestPath = normalizePrefix(webProperties.getAppApi().getPrefix())
                + "/print/miniapp/demo/image-collages";
        if (!properties.isEnabled()) return;
        try {
            multipartRoot = privateDirectory(properties.getMultipartRoot().normalize());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to initialize the private collage multipart directory", failure);
        }
        uploads = new Semaphore(properties.getMaxConcurrentUploads(), true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matchesTargetRequest(request.getContextPath(), request.getRequestURI())
                && !matchesStagedPart(request.getContextPath(), request.getRequestURI());
    }

    boolean matchesTargetRequest(String contextPath, String requestUri) {
        return (normalizeContextPath(contextPath) + targetRequestPath).equals(requestUri);
    }

    boolean matchesStagedPart(String contextPath, String requestUri) {
        String base = normalizeContextPath(contextPath) + normalizePrefix(webProperties.getAppApi().getPrefix())
                + "/print/miniapp/demo/image-collage-upload-sessions/";
        return requestUri != null && requestUri.startsWith(base)
                && requestUri.substring(base.length()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/parts/[0-8]");
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.strip();
        if (value.isEmpty() || "/".equals(value)) return "";
        if (!value.startsWith("/")) value = "/" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) return "";
        String value = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean stagedPart = matchesStagedPart(request.getContextPath(), request.getRequestURI());
        if (stagedPart && (!staged.isEnabled() || !"POST".equals(request.getMethod()))) {
            reject(response, 404, 1_935_001, "Staged collage upload is disabled");
            return;
        }
        if (!properties.isEnabled() || uploads == null || multipartRoot == null) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, stagedPart ? 1_935_001 : 1_934_001, "Miniapp demo image collage is disabled");
            return;
        }
        long declared = request.getContentLengthLong();
        long maximum = stagedPart ? 3L * 1024 * 1024 : properties.getMaxMultipartBytes();
        if (stagedPart && (declared <= 0 || request.getContentType() == null
                || !request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data;"))) {
            reject(response, 400, 1_935_002, "Invalid staged collage upload request");
            return;
        }
        if (declared > maximum) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, stagedPart ? 1_935_008 : 1_934_003,
                    "Miniapp demo image collage exceeds the input limit");
            return;
        }
        if (!uploads.tryAcquire()) {
            reject(response, 429, stagedPart ? 1_935_006 : 1_934_006,
                    "Miniapp demo image collage renderer is busy");
            return;
        }
        try {
            FileStore store = Files.getFileStore(multipartRoot);
            long reserve = Math.addExact(properties.getMinFreeBytes(), maximum);
            if (store.getUsableSpace() < reserve) {
                reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, stagedPart ? 1_935_007 : 1_934_010,
                        "Miniapp demo image collage storage capacity is low");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            uploads.release();
        }
    }

    private static Path privateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path);
            Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY);
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path).equals(PRIVATE_DIRECTORY)) {
            throw new IOException("Unsafe collage multipart directory");
        }
        return path.toRealPath();
    }

    private static void reject(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        String safe = message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"code\":" + code + ",\"data\":null,\"msg\":\"" + safe + "\"}");
    }
}
