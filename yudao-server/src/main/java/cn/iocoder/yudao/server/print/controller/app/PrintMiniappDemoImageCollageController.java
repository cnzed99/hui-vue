package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoImageCollageException;
import cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoImageCollageFileService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoImageCollageController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoImageCollageFileService collages;

    public PrintMiniappDemoImageCollageController(MiniappDemoSessionService sessions,
            MiniappDemoImageCollageFileService collages) {
        this.sessions = sessions; this.collages = collages;
    }

    @PostMapping(value = "/image-collages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-image-collage", operateName = "create")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> create(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request) throws ServletException, IOException {
        if (!query.isEmpty()) throw invalid();
        List<Part> parts = new ArrayList<>(request.getParts());
        if (files == null || files.isEmpty() || files.size() > 9 || parts.size() != files.size()) throw invalid();
        long total = 0;
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            MultipartFile file = files.get(index);
            if (!"files".equals(part.getName()) || part.getSubmittedFileName() == null || part.getSize() < 1
                    || file == null || file.isEmpty() || file.getSize() != part.getSize()) throw invalid();
            total = Math.addExact(total, part.getSize());
        }
        if (total < 1) throw invalid();
        var identity = sessions.requireForWrite(authorization, origin, host);
        return noStore(CommonResult.success(collages.create(identity, files, key)));
    }

    @ExceptionHandler(MiniappDemoImageCollageException.class)
    public ResponseEntity<CommonResult<Object>> collageError(MiniappDemoImageCollageException failure) {
        return error(failure.status().value(), failure.code(), failure.getMessage());
    }
    @ExceptionHandler(MiniappDemoFileException.class)
    public ResponseEntity<CommonResult<Object>> fileError(MiniappDemoFileException failure) {
        return error(failure.status().value(), failure.code(), failure.getMessage());
    }
    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException failure) {
        return error(failure.status().value(), failure.code(), failure.getMessage());
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return fileError(new MiniappDemoFileException(MiniappDemoFileException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler({ServletException.class, IOException.class, ArithmeticException.class})
    public ResponseEntity<CommonResult<Object>> requestError(Exception ignored) { return collageError(invalid()); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Object>> safeError(Exception ignored) {
        return collageError(new MiniappDemoImageCollageException(MiniappDemoImageCollageException.Reason.RENDER_UNAVAILABLE));
    }
    private static MiniappDemoImageCollageException invalid() {
        return new MiniappDemoImageCollageException(MiniappDemoImageCollageException.Reason.INVALID_INPUT);
    }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
    private static ResponseEntity<CommonResult<Object>> error(int status, int code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(code, message));
    }
}
