package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoFileController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoFileService files;
    public PrintMiniappDemoFileController(MiniappDemoSessionService sessions, MiniappDemoFileService files) {
        this.sessions = sessions;
        this.files = files;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-files", operateName = "upload")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> upload(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid();
        MiniappDemoIdentity identity = sessions.requireForWrite(authorization, origin, host);
        return noStore(CommonResult.success(files.upload(identity, file, key)));
    }

    @GetMapping("/file")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-files", operateName = "get")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 1 || query.get("id") == null || query.get("id").size() != 1) throw invalid();
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(files.get(identity, query.getFirst("id"))));
    }

    private static MiniappDemoFileException invalid() { return new MiniappDemoFileException(MiniappDemoFileException.Reason.INVALID_INPUT); }
    @ExceptionHandler(MiniappDemoFileException.class)
    public ResponseEntity<CommonResult<Object>> fileError(MiniappDemoFileException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(failure.code(), failure.getMessage()));
    }
    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(failure.code(), failure.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return fileError(new MiniappDemoFileException(MiniappDemoFileException.Reason.STORAGE_UNAVAILABLE));
    }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
