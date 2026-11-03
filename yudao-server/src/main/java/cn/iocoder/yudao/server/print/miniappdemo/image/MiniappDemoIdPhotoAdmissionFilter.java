package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public final class MiniappDemoIdPhotoAdmissionFilter extends OncePerRequestFilter {
    public static final String ADMITTED_IDENTITY = MiniappDemoIdPhotoAdmissionFilter.class.getName() + ".identity";
    public static final String ADMITTED_IMAGE = MiniappDemoIdPhotoAdmissionFilter.class.getName() + ".image";
    public static final String ADMITTED_SPECIFICATION = MiniappDemoIdPhotoAdmissionFilter.class.getName() + ".specification";
    public static final String ADMITTED_KEY = MiniappDemoIdPhotoAdmissionFilter.class.getName() + ".key";
    private static final String ROUTE = "/print/miniapp/demo/id-photo-files";
    private static final long PEAK_RESERVE = 8L * 1024 * 1024;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private final MiniappDemoIdPhotoProperties properties;
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoFileProperties fileProperties;
    private final MiniappDemoFileService files;
    private final String target;
    private final Semaphore requests = new Semaphore(1, true);
    private final Set<Owner> owners = new HashSet<>();
    private boolean closing;
    private Path multipartRoot;

    public MiniappDemoIdPhotoAdmissionFilter(WebProperties webProperties, MiniappDemoIdPhotoProperties properties,
            MiniappDemoSessionService sessions, MiniappDemoFileProperties fileProperties, MiniappDemoFileService files) {
        this.properties = properties; this.sessions = sessions; this.fileProperties = fileProperties; this.files = files;
        this.target = normalizePrefix(webProperties.getAppApi().getPrefix()) + ROUTE;
    }

    @PostConstruct
    public void initialize() throws IOException {
        properties.validateEnabled();
        if (!properties.isEnabled()) return;
        files.requireStorageLockHeld();
        multipartRoot = privateDirectory(fileProperties.getStorageRoot().normalize().resolve("id-photo-multipart"));
        try (var paths = Files.newDirectoryStream(multipartRoot, "task-*")) {
            for (Path path : paths) MiniappDemoIdPhotoMultipartRequest.deleteTaskDirectory(path, multipartRoot);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(normalizeContextPath(request.getContextPath()) + target).equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || multipartRoot == null) { reject(response, 404, 1_936_001); return; }
        if (!"POST".equals(request.getMethod())) { response.setHeader("Allow", "POST"); reject(response, 405, 1_936_002); return; }
        var lengths = Collections.list(request.getHeaders("Content-Length"));
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        long length = request.getContentLengthLong();
        if (request.getHeader("Transfer-Encoding") != null || request.getHeader("Content-Encoding") != null
                || lengths.size() != 1 || !lengths.get(0).matches("[0-9]+") || length <= 0
                || request.getQueryString() != null || keys.size() != 1 || !keys.get(0).matches("[!-~]{1,128}")) {
            reject(response, 400, 1_936_002); return;
        }
        try { MiniappDemoIdPhotoMultipartRequest.boundary(request.getContentType()); }
        catch (RuntimeException failure) { reject(response, 400, 1_936_002); return; }
        if (length > properties.getMaxMultipartBytes()) { reject(response, 413, 1_936_003); return; }
        if (!tryAdmit()) { reject(response, 429, 1_936_006); return; }
        Owner owner = null;
        boolean ownerHeld = false;
        MiniappDemoIdPhotoMultipartRequest multipart = null;
        try {
            MiniappDemoIdentity identity;
            try {
                identity = sessions.requireForWrite(request.getHeader("Authorization"), request.getHeader("Origin"),
                        request.getHeader("Referer"), request.getHeader("Host"));
            } catch (MiniappDemoSessionException failure) {
                reject(response, failure.status().value(), failure.code()); return;
            }
            owner = new Owner(identity.tenantId(), identity.storeId(), identity.principalId());
            synchronized (owners) { ownerHeld = owners.add(owner); }
            if (!ownerHeld) { reject(response, 429, 1_936_006); return; }
            try {
                files.requireStorageLockHeld();
            } catch (RuntimeException failure) {
                reject(response, 503, 1_936_008); return;
            }
            final long reserve;
            try {
                reserve = Math.addExact(fileProperties.getMinFreeBytes(),
                        Math.addExact(properties.getMaxMultipartBytes(), PEAK_RESERVE));
                if (Files.getFileStore(fileProperties.getStorageRoot()).getUsableSpace() < reserve) {
                    reject(response, 507, 1_936_010); return;
                }
            } catch (ArithmeticException | IOException failure) {
                reject(response, 503, 1_936_008); return;
            }
            try {
                multipart = MiniappDemoIdPhotoMultipartRequest.parse(request, multipartRoot, properties.getMaxInputBytes());
            } catch (MiniappDemoIdPhotoMultipartRequest.PartTooLarge failure) {
                reject(response, 413, 1_936_003); return;
            } catch (MiniappDemoIdPhotoMultipartRequest.UnsupportedPart failure) {
                reject(response, 415, 1_936_004); return;
            } catch (IllegalArgumentException | ServletException | IOException failure) {
                reject(response, 400, 1_936_002); return;
            }
            multipart.setAttribute(ADMITTED_IDENTITY, identity);
            multipart.setAttribute(ADMITTED_IMAGE, multipart.image());
            multipart.setAttribute(ADMITTED_SPECIFICATION, multipart.specification());
            multipart.setAttribute(ADMITTED_KEY, keys.get(0));
            chain.doFilter(multipart, response);
        } finally {
            if (multipart != null) multipart.cleanup(multipartRoot);
            if (ownerHeld) {
                synchronized (owners) { owners.remove(owner); }
            }
            requests.release();
        }
    }

    private synchronized boolean tryAdmit() { return !closing && requests.tryAcquire(); }

    @PreDestroy
    public void drain() {
        synchronized (this) { closing = true; }
        boolean drained = false;
        try { drained = requests.tryAcquire(1, properties.getRenderTimeoutSeconds() + 5L, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        if (!drained) files.retainStorageLockUntilProcessExit();
    }

    private static Path privateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path); Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY);
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path).equals(PRIVATE_DIRECTORY)) {
            throw new IOException("Unsafe id photo multipart directory");
        }
        return path.toRealPath();
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

    private static void reject(HttpServletResponse response, int status, int code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.getWriter().write("{\"code\":" + code + ",\"data\":null,\"msg\":\"Id photo upload rejected\"}");
    }

    private record Owner(long tenant, long store, String principal) { }
}
