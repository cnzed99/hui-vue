package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.archive.MiniappDemoArchiveAdmissionFilter;
import cn.iocoder.yudao.server.print.miniappdemo.archive.StrictZipPdf;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.server.print.miniappdemo.archive.MiniappDemoArchiveAdmissionFilter.*;
import static cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException.Reason.*;

@RestController
@RequestMapping(MiniappDemoArchiveAdmissionFilter.BASE)
@TenantIgnore
public class PrintMiniappDemoArchiveController {
    private final MiniappDemoFileService files;

    public PrintMiniappDemoArchiveController(MiniappDemoFileService files) {
        this.files = files;
    }

    @PostMapping(value = INSPECT, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-archive", operateName = "inspect")
    public ResponseEntity<CommonResult<Inspection>> inspect(HttpServletRequest request) throws IOException, ServletException {
        MiniappDemoIdentity identity = identity(request);
        Map<String, Part> parts = parts(request, Set.of("archive"));
        StrictZipPdf archive = readArchive(parts.get("archive"), deadline());
        return success(new Inspection(archive.sha256(), identity.expiresAtMs(), archive.candidates()));
    }

    @PostMapping(value = SELECT, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-archive", operateName = "select")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> select(HttpServletRequest request) throws IOException, ServletException {
        MiniappDemoIdentity identity = identity(request);
        List<String> keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.get(0).matches("[!-~]{1,128}")) throw invalid();
        Map<String, Part> parts = parts(request, Set.of("archive", "archiveSha256", "ordinal"));
        String sha = asciiField(parts.get("archiveSha256"), 64);
        String ordinal = asciiField(parts.get("ordinal"), 2);
        if (!sha.matches("[0-9a-f]{64}") || !ordinal.matches("0|[1-9][0-9]?")) throw invalid();
        int index = Integer.parseInt(ordinal);
        long expires = deadline();
        StrictZipPdf archive = readArchive(parts.get("archive"), expires);
        if (!archive.sha256().equals(sha)) throw invalid();
        byte[] pdf = archive.extract(index);
        String name = archive.candidates().stream().filter(item -> item.ordinal() == index).findFirst()
                .orElseThrow(PrintMiniappDemoArchiveController::invalid).displayName();
        return success(files.uploadFromArchive(identity, new ExtractedPdf(name, pdf), keys.get(0), sha, index, expires));
    }

    private static MiniappDemoIdentity identity(HttpServletRequest request) {
        if (request.getAttribute(ADMITTED) instanceof MiniappDemoIdentity identity) return identity;
        throw invalid();
    }

    private static Map<String, Part> parts(HttpServletRequest request, Set<String> required) throws IOException, ServletException {
        Map<String, Part> found = new HashMap<>();
        for (Part part : request.getParts()) {
            String name = part.getName();
            if (!required.contains(name) || found.putIfAbsent(name, part) != null) throw invalid();
            if ("archive".equals(name) != (part.getSubmittedFileName() != null)) throw invalid();
        }
        if (!found.keySet().equals(required)) throw invalid();
        return found;
    }

    private static String asciiField(Part part, int max) throws IOException {
        if (part.getSize() > max) throw invalid();
        try (InputStream input = part.getInputStream()) {
            byte[] value = input.readNBytes(max + 1);
            if (value.length > max) throw invalid();
            return new String(value, StandardCharsets.US_ASCII);
        }
    }

    private static StrictZipPdf readArchive(Part part, long deadline) throws IOException {
        if (part.getSize() > StrictZipPdf.MAX_BYTES) throw new MiniappDemoFileException(TOO_LARGE);
        try (InputStream input = part.getInputStream()) {
            return StrictZipPdf.read(input, deadline);
        }
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    private static MiniappDemoFileException invalid() {
        return new MiniappDemoFileException(INVALID_INPUT);
    }

    @ExceptionHandler({StrictZipPdf.InvalidArchive.class, ServletException.class})
    public ResponseEntity<CommonResult<Object>> invalidArchive() {
        return fileError(invalid());
    }

    @ExceptionHandler({StrictZipPdf.DeadlineExceeded.class, IOException.class})
    public ResponseEntity<CommonResult<Object>> unavailable() {
        return fileError(new MiniappDemoFileException(STORAGE_UNAVAILABLE));
    }

    @ExceptionHandler(MiniappDemoFileException.class)
    public ResponseEntity<CommonResult<Object>> fileError(MiniappDemoFileException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(failure.code(), failure.getMessage()));
    }

    private static <T> ResponseEntity<CommonResult<T>> success(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.success(data));
    }

    public record Inspection(String archiveSha256, long expiresAtMs, List<StrictZipPdf.Candidate> candidates) { }

    private record ExtractedPdf(String filename, byte[] bytes) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return MediaType.APPLICATION_PDF_VALUE; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File destination) { throw new UnsupportedOperationException(); }
    }
}
