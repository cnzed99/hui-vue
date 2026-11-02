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
    private Semaphore uploads;
    private Path multipartRoot;
    private String targetRequestPath;

    public MiniappDemoImageCollageUploadAdmissionFilter(MiniappDemoImageCollageProperties properties,
            WebProperties webProperties) {
        this.properties = properties;
        this.webProperties = webProperties;
    }

    @PostConstruct
    public void initialize() throws IOException {
        properties.validateEnabled();
        targetRequestPath = normalizePrefix(webProperties.getAppApi().getPrefix())
                + "/print/miniapp/demo/image-collages";
        if (!properties.isEnabled()) return;
        multipartRoot = privateDirectory(properties.getMultipartRoot().normalize());
        uploads = new Semaphore(properties.getMaxConcurrentUploads(), true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matchesTargetRequest(request.getContextPath(), request.getRequestURI());
    }

    boolean matchesTargetRequest(String contextPath, String requestUri) {
        return (normalizeContextPath(contextPath) + targetRequestPath).equals(requestUri);
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
        if (!properties.isEnabled() || uploads == null || multipartRoot == null) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, 1_934_001, "Miniapp demo image collage is disabled");
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > properties.getMaxMultipartBytes()) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, 1_934_003,
                    "Miniapp demo image collage exceeds the input limit");
            return;
        }
        if (!uploads.tryAcquire()) {
            reject(response, 429, 1_934_006,
                    "Miniapp demo image collage renderer is busy");
            return;
        }
        try {
            FileStore store = Files.getFileStore(multipartRoot);
            long reserve = Math.addExact(properties.getMinFreeBytes(), properties.getMaxMultipartBytes());
            if (store.getUsableSpace() < reserve) {
                reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 1_934_010,
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
