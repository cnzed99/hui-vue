package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.file.PrintFileException;
import cn.iocoder.yudao.server.print.file.PrintFileMetadata;
import cn.iocoder.yudao.server.print.file.PrintFileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/print/files")
public class PrintFileController {
    private final PrintFileService files;
    public PrintFileController(PrintFileService files) { this.files = files; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ss.hasPermission('print:file:upload')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-files", operateName = "upload")
    public CommonResult<PrintFileMetadata> upload(@RequestPart(value = "file", required = false) MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return CommonResult.success(files.upload(file, key));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('print:file:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-files", operateName = "metadata")
    public CommonResult<PrintFileMetadata> metadata(@PathVariable String id) {
        return CommonResult.success(files.get(id));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("@ss.hasPermission('print:file:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-files", operateName = "download")
    public ResponseEntity<InputStreamResource> content(@PathVariable String id) {
        PrintFileService.OpenContent content = files.open(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.metadata().sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.metadata().displayName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('print:file:delete')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-files", operateName = "delete")
    public CommonResult<PrintFileMetadata> delete(@PathVariable String id) {
        return CommonResult.success(files.delete(id));
    }

    @ExceptionHandler(PrintFileException.class)
    public ResponseEntity<CommonResult<Object>> fileError(PrintFileException failure) {
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<CommonResult<Object>> databaseError(DataAccessException ignored) {
        return fileError(new PrintFileException(PrintFileException.Reason.STORAGE_UNAVAILABLE));
    }
}
