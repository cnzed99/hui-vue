package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileVariant;
import cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoAdmissionFilter;
import cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoException;
import cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoFileService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoIdPhotoFileController {
    private final MiniappDemoIdPhotoFileService files;

    public PrintMiniappDemoIdPhotoFileController(MiniappDemoIdPhotoFileService files) { this.files = files; }

    @PostMapping(value = "/id-photo-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-id-photo", operateName = "create")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> create(HttpServletRequest request) {
        Object rawIdentity = request.getAttribute(MiniappDemoIdPhotoAdmissionFilter.ADMITTED_IDENTITY);
        Object rawImage = request.getAttribute(MiniappDemoIdPhotoAdmissionFilter.ADMITTED_IMAGE);
        Object rawSpecification = request.getAttribute(MiniappDemoIdPhotoAdmissionFilter.ADMITTED_SPECIFICATION);
        Object rawKey = request.getAttribute(MiniappDemoIdPhotoAdmissionFilter.ADMITTED_KEY);
        if (!(rawIdentity instanceof MiniappDemoIdentity identity) || !(rawImage instanceof MultipartFile image)
                || !(rawSpecification instanceof String specification) || !(rawKey instanceof String key)) throw invalid();
        MiniappDemoFileVariant variant;
        try { variant = MiniappDemoFileVariant.valueOf(specification); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        return noStore(CommonResult.success(files.create(identity, image, variant, key)));
    }

    @ExceptionHandler(MiniappDemoIdPhotoException.class)
    public ResponseEntity<CommonResult<Object>> idPhotoError(MiniappDemoIdPhotoException failure) {
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
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Object>> safeError(Exception ignored) {
        return idPhotoError(new MiniappDemoIdPhotoException(MiniappDemoIdPhotoException.Reason.RENDER_UNAVAILABLE));
    }

    private static MiniappDemoIdPhotoException invalid() {
        return new MiniappDemoIdPhotoException(MiniappDemoIdPhotoException.Reason.INVALID_INPUT);
    }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
    private static ResponseEntity<CommonResult<Object>> error(int status, int code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(code, message));
    }
}
