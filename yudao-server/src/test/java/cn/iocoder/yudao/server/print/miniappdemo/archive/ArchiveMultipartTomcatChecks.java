package cn.iocoder.yudao.server.print.miniappdemo.archive;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ArchiveMultipartTomcatChecks {
    private static final String BASE = "/app-api/print/miniapp-demo";
    private static final String INSPECT = BASE + "/archive-inspections";
    private static final String SELECT = BASE + "/archive-files";
    private static final String BOUNDARY = "----WebKitFormBoundaryCodexFixture23";
    private static final String TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static final AtomicInteger NATIVE_PARTS = new AtomicInteger();
    private static final AtomicInteger BODY_STREAMS = new AtomicInteger();
    private static final CountDownLatch SLOW_READS = new CountDownLatch(2);
    private static final CountDownLatch FULL_READS = new CountDownLatch(2);
    private static final CountDownLatch RELEASE_FULL_READS = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]).toRealPath();
        if (!fixture.getFileName().toString().startsWith("print-zip-tomcat-")) throw new IllegalArgumentException();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(fixture.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", fixture.toString());
        var properties = new MiniappDemoFileProperties();
        properties.setEnabled(true);
        properties.setStorageRoot(fixture);
        var transaction = new DataSourceTransactionManager();
        var files = new MiniappDemoFileService(properties, null, null, null, null, transaction) {
            @Override public void requireStorageLockHeld() { }
        };
        var sessions = new MiniappDemoSessionService(null, null, transaction) {
            @Override public MiniappDemoIdentity requireForWrite(String auth, String origin, String referer, String host) {
                if (auth == null || !auth.startsWith("fixture-")) {
                    throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);
                }
                return new MiniappDemoIdentity("session", auth, 1, 1, Long.MAX_VALUE);
            }
        };
        addFilter(context, "native-counter", (request, response, chain) -> chain.doFilter(
                new HttpServletRequestWrapper((HttpServletRequest) request) {
                    @Override public Collection<Part> getParts() {
                        NATIVE_PARTS.incrementAndGet();
                        throw new AssertionError("Native getParts invoked");
                    }
                    @Override public Part getPart(String name) {
                        NATIVE_PARTS.incrementAndGet();
                        throw new AssertionError("Native getPart invoked");
                    }
                    @Override public ServletInputStream getInputStream() throws IOException {
                        BODY_STREAMS.incrementAndGet();
                        if ("1".equals(getHeader("X-Slow-Fixture"))) SLOW_READS.countDown();
                        return super.getInputStream();
                    }
                }, response));
        addFilter(context, "archive-admission", new MiniappDemoArchiveAdmissionFilter(new WebProperties(), sessions, properties, files));
        Tomcat.addServlet(context, "fixture", new HttpServlet() {
            @Override protected void doPost(HttpServletRequest request, HttpServletResponse response)
                    throws IOException, jakarta.servlet.ServletException {
                if (!(request instanceof MultipartHttpServletRequest multipart)) throw new AssertionError("No multipart marker");
                if (request.getAttribute(MiniappDemoArchiveAdmissionFilter.ADMITTED) == null) throw new AssertionError("Not admitted");
                Part archive = multipart.getPart("archive");
                if (archive == null || archive.getSize() == 0 || request.getParameterMap().size() != 0) {
                    throw new AssertionError("Unexpected archive fixture body");
                }
                if ("1".equals(request.getHeader("X-Full-Fixture"))) {
                    byte[] pdf;
                    try (var input = archive.getInputStream()) {
                        pdf = StrictZipPdf.read(input, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)).extract(0);
                    }
                    FULL_READS.countDown();
                    try {
                        check(RELEASE_FULL_READS.await(5, TimeUnit.SECONDS), "full uploads released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    check(pdf.length == 2_000_000, "full PDF retained correctly");
                } else {
                    try (var input = archive.getInputStream()) {
                        String payload = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
                        check(payload.equals("zip-bytes\r\n--" + BOUNDARY.substring(0, 30) + "X"),
                                "boundary-like binary bytes retained");
                    }
                }
                response.setStatus(204);
            }
        });
        context.addServletMappingDecoded("/", "fixture");
        tomcat.start();
        try {
            int port = tomcat.getConnector().getLocalPort();
            byte[] archive = bytes(part("archive", "fixture.zip", "zip-bytes\r\n--" + BOUNDARY.substring(0, 30) + "X"));
            byte[] inspection = join(archive, bytes(end()));
            byte[] selection = join(archive, bytes(part("archiveSha256", null, "a".repeat(64))
                    + part("ordinal", null, "1") + end()));
            int initial = BODY_STREAMS.get();
            check(post(port, INSPECT, inspection, null) == 401 && BODY_STREAMS.get() == initial, "auth before reading");
            check(post(port, INSPECT, inspection, "fixture-a") == 204, "browser-style inspection");
            check(post(port, SELECT, selection, "fixture-a") == 204, "browser-style selection");
            check(post(port, INSPECT, join(archive, archive, bytes(end())), "fixture-a") == 400, "duplicate field");
            check(post(port, INSPECT, archive, "fixture-a") == 400, "missing closing delimiter");
            check(post(port, INSPECT, bytes("--" + BOUNDARY + "\r\nX-Large: " + "a".repeat(1100) + "\r\n\r\na"
                    + "\r\n" + end()), "fixture-a") == 400, "part header bound");
            check(post(port, INSPECT, bytes(part("archive", "fixture.zip", "a".repeat(2 * 1024 * 1024 + 1)) + end()),
                    "fixture-a") == 413, "archive part limit");
            check(post(port, INSPECT, new byte[2100 * 1024 + 1], "fixture-a") == 413, "request limit");
            try (Socket first = slow(port, SELECT, "fixture-a")) {
                try (Socket second = slow(port, INSPECT, "fixture-b")) {
                    check(SLOW_READS.await(5, TimeUnit.SECONDS), "two body readers admitted");
                    int admitted = BODY_STREAMS.get();
                    check(post(port, INSPECT, inspection, "fixture-c") == 429 && BODY_STREAMS.get() == admitted, "third request rejected before read");
                }
            }
            boolean recovered = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                if (post(port, INSPECT, inspection, "fixture-c") == 204) {
                    recovered = true;
                    break;
                }
                Thread.sleep(100);
            }
            check(recovered, "disconnect releases permit");
            byte[] full = fullBody();
            var workers = Executors.newFixedThreadPool(2);
            try {
                var one = workers.submit(() -> post(port, INSPECT, full, "fixture-a", true));
                var two = workers.submit(() -> post(port, INSPECT, full, "fixture-b", true));
                check(FULL_READS.await(5, TimeUnit.SECONDS), "two full ZIPs and extracted PDFs coexist");
                long usedHeap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                RELEASE_FULL_READS.countDown();
                check(one.get(5, TimeUnit.SECONDS) == 204 && two.get(5, TimeUnit.SECONDS) == 204, "full uploads completed");
                System.out.println("ZIP_TOMCAT_FULL_CONCURRENCY heap_used_bytes=" + usedHeap
                        + " max_heap_bytes=" + Runtime.getRuntime().maxMemory());
            } finally {
                RELEASE_FULL_READS.countDown();
                workers.shutdownNow();
            }
            check(NATIVE_PARTS.get() == 0, "native getParts never invoked");
            System.out.println("ZIP_TOMCAT_MULTIPART_PASS native_getParts=0 disconnect_recovered=true");
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static void addFilter(Context context, String name, jakarta.servlet.Filter filter) {
        FilterDef definition = new FilterDef();
        definition.setFilterName(name);
        definition.setFilterClass(filter.getClass().getName());
        definition.setFilter(filter);
        context.addFilterDef(definition);
        FilterMap mapping = new FilterMap();
        mapping.setFilterName(name);
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);
    }

    private static int post(int port, String uri, byte[] body, String auth) throws Exception {
        return post(port, uri, body, auth, false);
    }

    private static int post(int port, String uri, byte[] body, String auth, boolean full) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri))
                .version(HttpClient.Version.HTTP_1_1).header("Content-Type", TYPE)
                .timeout(java.time.Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth != null) request.header("Authorization", auth);
        if (full) request.header("X-Full-Fixture", "1");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static Socket slow(int port, String uri, String auth) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.getOutputStream().write(bytes("POST " + uri + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Authorization: " + auth + "\r\nContent-Type: " + TYPE + "\r\nContent-Length: 2000000\r\n"
                + "X-Slow-Fixture: 1\r\n\r\n--" + BOUNDARY + "\r\n"));
        socket.getOutputStream().flush();
        return socket;
    }

    private static String part(String name, String filename, String content) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"\r\nContent-Type: application/zip")
                + "\r\n\r\n" + content + "\r\n";
    }

    private static String end() { return "--" + BOUNDARY + "--\r\n"; }

    private static byte[] fullBody() throws IOException {
        byte[] pdf = new byte[2_000_000];
        System.arraycopy(bytes("%PDF-"), 0, pdf, 0, 5);
        var crc = new java.util.zip.CRC32();
        crc.update(pdf);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            var entry = new java.util.zip.ZipEntry("large.pdf");
            entry.setMethod(java.util.zip.ZipEntry.STORED);
            entry.setCrc(crc.getValue());
            entry.setSize(pdf.length);
            entry.setCompressedSize(pdf.length);
            zip.putNextEntry(entry);
            zip.write(pdf);
            zip.closeEntry();
        }
        String prefix = part("archive", "fixture.zip", "");
        return join(bytes(prefix.substring(0, prefix.length() - 2)), bytes.toByteArray(), bytes("\r\n" + end()));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] join(byte[]... parts) {
        var result = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) result.writeBytes(part);
        return result.toByteArray();
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
