package cn.iocoder.yudao.server.print.miniappdemo.archive;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class MiniappDemoArchiveAdmissionFilter extends OncePerRequestFilter {
    public static final String BASE = "/print/miniapp-demo";
    public static final String INSPECT = "/archive-inspections";
    public static final String SELECT = "/archive-files";
    public static final String ADMITTED = MiniappDemoArchiveAdmissionFilter.class.getName() + ".identity";
    private static final long MAX_BODY = 2100L * 1024;
    private static final long DISK_RESERVE = 64L * 1024 * 1024;
    private final String base;
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoFileProperties properties;
    private final MiniappDemoFileService files;
    private final Semaphore requests = new Semaphore(2);
    private final Set<Owner> selections = new HashSet<>();
    private boolean closing;

    public MiniappDemoArchiveAdmissionFilter(WebProperties webProperties, MiniappDemoSessionService sessions,
            MiniappDemoFileProperties properties, MiniappDemoFileService files) {
        this.base = webProperties.getAppApi().getPrefix() + BASE;
        this.sessions = sessions;
        this.properties = properties;
        this.files = files;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String route = request.getContextPath() + base;
        return !matches(request.getRequestURI(), route) && !matches(request.getServletPath(), base);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matches(request.getRequestURI(), request.getContextPath() + base)) {
            reject(response, 404, 1927002);
            return;
        }
        if (!properties.isEnabled()) {
            reject(response, 404, 1927001);
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            reject(response, 405, 1927002);
            return;
        }
        var lengths = Collections.list(request.getHeaders("Content-Length"));
        long length = request.getContentLengthLong();
        if (request.getHeader("Transfer-Encoding") != null || request.getHeader("Content-Encoding") != null
                || lengths.size() != 1 || !lengths.get(0).matches("[0-9]+") || length <= 0
                || request.getQueryString() != null || !validContentType(request.getContentType())) {
            reject(response, 400, 1927002);
            return;
        }
        if (length > MAX_BODY) {
            reject(response, 413, 1927003);
            return;
        }
        if (!tryAdmit()) {
            reject(response, 429, 1927004);
            return;
        }
        Owner owner = null;
        boolean selectionHeld = false;
        try {
            MiniappDemoIdentity identity;
            try {
                identity = sessions.requireForWrite(request.getHeader("Authorization"), request.getHeader("Origin"),
                        request.getHeader("Referer"), request.getHeader("Host"));
            } catch (MiniappDemoSessionException failure) {
                reject(response, failure.status().value(), failure.code());
                return;
            }
            if (request.getRequestURI().endsWith(SELECT)) {
                owner = new Owner(identity.tenantId(), identity.storeId(), identity.principalId());
                synchronized (selections) {
                    selectionHeld = selections.add(owner);
                }
                if (!selectionHeld) {
                    reject(response, 429, 1927005);
                    return;
                }
            }
            files.requireStorageLockHeld();
            if (Files.getFileStore(properties.getStorageRoot()).getUsableSpace()
                    < properties.getMinFreeBytes() + DISK_RESERVE) {
                reject(response, 507, 1927006);
                return;
            }
            request.setAttribute(ADMITTED, identity);
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(ADMITTED);
            if (selectionHeld) {
                synchronized (selections) {
                    selections.remove(owner);
                }
            }
            requests.release();
        }
    }

    private static boolean matches(String path, String prefix) {
        return (prefix + INSPECT).equals(path) || (prefix + SELECT).equals(path);
    }

    private synchronized boolean tryAdmit() {
        return !closing && requests.tryAcquire();
    }

    @PreDestroy
    public void drain() {
        synchronized (this) {
            closing = true;
        }
        try {
            if (requests.tryAcquire(2, 5, TimeUnit.SECONDS)) return;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
        files.retainStorageLockUntilProcessExit();
    }

    private static boolean validContentType(String value) {
        if (value == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(value);
            return MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(type)
                    && type.getParameter("boundary") != null && !type.getParameter("boundary").isBlank();
        } catch (InvalidMediaTypeException failure) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, int status, int code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.getWriter().write("{\"code\":" + code + ",\"data\":null,\"msg\":\"Archive upload rejected\"}");
    }

    private record Owner(long tenant, long store, String principal) { }
}
