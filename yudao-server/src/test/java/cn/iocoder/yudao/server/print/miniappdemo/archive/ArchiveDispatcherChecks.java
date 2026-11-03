package cn.iocoder.yudao.server.print.miniappdemo.archive;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.server.print.controller.app.PrintMiniappDemoArchiveController;
import cn.iocoder.yudao.server.print.controller.app.PrintMiniappDemoFileController;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveDispatcherChecks {
    private static final String ARCHIVE = "/app-api/print/miniapp-demo/archive-";
    private static final String ORDINARY = "/app-api/print/miniapp/demo/files";
    private static final String BOUNDARY = "----WebKitFormBoundaryArchiveFixture";
    private static final String TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static final AtomicInteger ARCHIVE_PARTS = new AtomicInteger();
    private static final AtomicInteger ORDINARY_PARTS = new AtomicInteger();
    private static final AtomicInteger ARCHIVE_READS = new AtomicInteger();
    private static final AtomicInteger ARCHIVE_WRITES = new AtomicInteger();
    private static final CountDownLatch SLOW = new CountDownLatch(2);
    private static final byte[] PDF = ascii("%PDF-1.4\nfixture\n");

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]).toRealPath();
        if (!scratch.getFileName().toString().startsWith("print-zip-dispatch-")) throw new IllegalArgumentException();
        try (var context = new AnnotationConfigServletWebServerApplicationContext();
             var tempWatcher = scratch.getFileSystem().newWatchService()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",
                    Map.of("fixture.root", scratch.toString())));
            context.register(Fixture.class);
            context.refresh();
            int port = context.getWebServer().getPort();
            Path multipart = scratch.resolve("multipart");
            multipart.register(tempWatcher, StandardWatchEventKinds.ENTRY_CREATE);
            byte[] zip = zip();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
            byte[] inspection = body(part("archive", "fixture.zip", zip));
            byte[] selection = body(part("archive", "fixture.zip", zip), part("archiveSha256", null, ascii(sha)),
                    part("ordinal", null, ascii("0")));
            int initial = ARCHIVE_READS.get();
            check(post(port, ARCHIVE + "inspections", inspection, null).statusCode() == 401
                    && ARCHIVE_READS.get() == initial, "unauthorized body unopened");
            var inspected = post(port, ARCHIVE + "inspections", inspection, "fixture-a");
            check(inspected.statusCode() == 200 && inspected.body().contains(sha)
                    && inspected.body().contains("candidate.pdf"), "actual inspection: " + inspected.body());
            var selected = post(port, ARCHIVE + "files", selection, "fixture-a");
            check(selected.statusCode() == 200 && selected.body().contains("fixture-archive-id")
                    && ARCHIVE_WRITES.get() == 1, "actual selection: " + selected.body());
            check(empty(multipart), "archive paths create no shared multipart files");
            check(post(port, ARCHIVE + "files", part("archive", "fixture.zip", zip), "fixture-a").statusCode() == 400,
                    "malformed body rejected");
            try (Socket first = slow(port, ARCHIVE + "inspections", "fixture-a");
                 Socket second = slow(port, ARCHIVE + "files", "fixture-b")) {
                check(SLOW.await(5, TimeUnit.SECONDS), "two admitted archive bodies");
                int admitted = ARCHIVE_READS.get();
                check(post(port, ARCHIVE + "inspections", inspection, "fixture-c").statusCode() == 429
                        && ARCHIVE_READS.get() == admitted, "third archive body unopened");
            }
            boolean restored = false;
            for (int i = 0; i < 20; i++) {
                if (post(port, ARCHIVE + "inspections", inspection, "fixture-c").statusCode() == 200) {
                    restored = true;
                    break;
                }
                Thread.sleep(100);
            }
            check(restored && empty(multipart), "disconnect recovers without shared residue");
            check(ARCHIVE_PARTS.get() == 0, "new routes avoid native getParts");
            check(tempWatcher.poll() == null, "archive routes never create a shared temp file");
            var ordinary = post(port, ORDINARY, body(part("file", "ordinary.pdf", PDF)), "fixture-a");
            check(ordinary.statusCode() == 200 && ordinary.body().contains("fixture-ordinary-id")
                    && ORDINARY_PARTS.get() > 0, "legacy native multipart: " + ordinary.body());
            for (int i = 0; i < 20 && !empty(multipart); i++) Thread.sleep(50);
            check(empty(multipart), "legacy temp cleanup");
            check(tempWatcher.poll(1, TimeUnit.SECONDS) != null, "watcher observes ordinary temp creation");
            System.out.println("ZIP_DISPATCHER_INTEGRATION_PASS archive_native_getParts=" + ARCHIVE_PARTS
                    + " ordinary_native_getParts=" + ORDINARY_PARTS + " archive_shared_temp_created=0 shared_multipart_residue=0");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    static class Fixture {
        @Bean TomcatServletWebServerFactory tomcat(Environment env) throws IOException {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getByName("127.0.0.1"));
            factory.setBaseDirectory(Files.createDirectory(root(env).resolve("tomcat")).toFile());
            return factory;
        }

        @Bean MultipartConfigElement multipartConfig(Environment env) throws IOException {
            Path temp = Files.createDirectory(root(env).resolve("multipart"));
            return new MultipartConfigElement(temp.toString(), 16 * 1024 * 1024, 32 * 1024 * 1024, 0);
        }

        @Bean StandardServletMultipartResolver multipartResolver() { return new StandardServletMultipartResolver(); }

        @Bean WebProperties webProperties() {
            var web = new WebProperties();
            web.setAdminUi(new WebProperties.Ui());
            return web;
        }

        @Bean WebMvcRegistrations projectMappings(WebProperties web) {
            return new YudaoWebAutoConfiguration().webMvcRegistrations(web);
        }

        @Bean FilterRegistrationBean<?> projectCors() { return new YudaoWebAutoConfiguration().corsFilterBean(); }

        @Bean FilterRegistrationBean<?> projectBodyCache() { return new YudaoWebAutoConfiguration().requestBodyCacheFilter(); }

        @Bean FilterRegistrationBean<?> nativeProbe() {
            return YudaoWebAutoConfiguration.createFilterBean((request, response, chain) -> chain.doFilter(
                    new HttpServletRequestWrapper((HttpServletRequest) request) {
                        @Override public Collection<Part> getParts() throws IOException, ServletException {
                            if (getRequestURI().startsWith(ARCHIVE)) ARCHIVE_PARTS.incrementAndGet();
                            if (getRequestURI().equals(ORDINARY)) ORDINARY_PARTS.incrementAndGet();
                            return super.getParts();
                        }

                        @Override public ServletInputStream getInputStream() throws IOException {
                            if (getRequestURI().startsWith(ARCHIVE)) {
                                ARCHIVE_READS.incrementAndGet();
                                if ("1".equals(getHeader("X-Slow-Fixture"))) SLOW.countDown();
                            }
                            return super.getInputStream();
                        }
                    }, response), Integer.MIN_VALUE + 10);
        }

        @Bean MiniappDemoFileProperties fileProperties(Environment env) {
            var props = new MiniappDemoFileProperties();
            props.setEnabled(true);
            props.setStorageRoot(root(env));
            return props;
        }

        @Bean MiniappDemoFileService files(MiniappDemoFileProperties props) {
            return new MiniappDemoFileService(props, null, null, null, null, new DataSourceTransactionManager()) {
                @Override public void start() { }
                @Override public void stop() { }
                @Override public void requireStorageLockHeld() { }

                @Override public MiniappDemoFileMetadata uploadFromArchive(MiniappDemoIdentity identity,
                        MultipartFile file, String key, String digest, int ordinal, long deadline) {
                    check(file.getSize() == PDF.length && ordinal == 0 && key.equals("fixture-key"), "archive write contract");
                    ARCHIVE_WRITES.incrementAndGet();
                    return metadata("fixture-archive-id");
                }

                @Override public MiniappDemoFileMetadata upload(MiniappDemoIdentity identity, MultipartFile file, String key) {
                    try {
                        check(!empty(props.getStorageRoot().resolve("multipart")), "legacy upload has temp file during handling");
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                    check(file.getSize() == PDF.length, "ordinary upload size");
                    return metadata("fixture-ordinary-id");
                }
            };
        }

        @Bean MiniappDemoSessionService sessions() {
            return new MiniappDemoSessionService(null, null, new DataSourceTransactionManager()) {
                @Override public MiniappDemoIdentity requireForWrite(String auth, String origin, String referer, String host) {
                    if (auth == null || !auth.startsWith("fixture-")) {
                        throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);
                    }
                    return new MiniappDemoIdentity("fixture-session", auth, 1, 1, Long.MAX_VALUE);
                }
            };
        }

        @Bean MiniappDemoArchiveAdmissionFilter archiveFilter(WebProperties web, MiniappDemoSessionService sessions,
                MiniappDemoFileProperties props, MiniappDemoFileService files) {
            return new MiniappDemoArchiveAdmissionFilter(web, sessions, props, files);
        }

        @Bean PrintMiniappDemoArchiveController archiveController(MiniappDemoFileService files) {
            return new PrintMiniappDemoArchiveController(files);
        }

        @Bean PrintMiniappDemoFileController ordinaryController(MiniappDemoSessionService sessions, MiniappDemoFileService files) {
            return new PrintMiniappDemoFileController(sessions, files);
        }

        private static Path root(Environment env) { return Path.of(env.getRequiredProperty("fixture.root")); }
    }

    private static MiniappDemoFileMetadata metadata(String id) {
        return new MiniappDemoFileMetadata(id, "fixture.pdf", PDF.length, null, "UPLOADED", null, 1, 2, false);
    }

    private static boolean empty(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { return paths.findAny().isEmpty(); }
    }

    private static HttpResponse<String> post(int port, String route, byte[] body, String auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
                .version(HttpClient.Version.HTTP_1_1).header("Content-Type", TYPE).header("Idempotency-Key", "fixture-key")
                .timeout(java.time.Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth != null) request.header("Authorization", auth);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Socket slow(int port, String route, String auth) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.getOutputStream().write(ascii("POST " + route + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: "
                + auth + "\r\nContent-Type: " + TYPE + "\r\nContent-Length: 2000000\r\nX-Slow-Fixture: 1\r\n\r\n--"
                + BOUNDARY + "\r\n"));
        return socket;
    }

    private static byte[] part(String name, String filename, byte[] bytes) {
        var result = new ByteArrayOutputStream();
        result.writeBytes(ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream")
                + "\r\n\r\n"));
        result.writeBytes(bytes);
        result.writeBytes(ascii("\r\n"));
        return result.toByteArray();
    }

    private static byte[] body(byte[]... parts) {
        var result = new ByteArrayOutputStream();
        for (byte[] part : parts) result.writeBytes(part);
        result.writeBytes(ascii("--" + BOUNDARY + "--\r\n"));
        return result.toByteArray();
    }

    private static byte[] zip() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            var crc = new CRC32();
            crc.update(PDF);
            var entry = new ZipEntry("candidate.pdf");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(PDF.length);
            entry.setCompressedSize(PDF.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(PDF);
        }
        return output.toByteArray();
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
