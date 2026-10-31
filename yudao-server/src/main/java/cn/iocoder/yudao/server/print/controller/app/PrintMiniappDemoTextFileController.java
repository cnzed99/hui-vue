package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextException;
import cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextFileService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoTextFileController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoTextFileService texts;
    private final ObjectMapper mapper;
    public PrintMiniappDemoTextFileController(MiniappDemoSessionService sessions, MiniappDemoTextFileService texts, ObjectMapper mapper) {
        this.sessions = sessions; this.texts = texts; this.mapper = mapper;
    }
    @PostMapping("/text-files")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-text", operateName = "create")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> create(
            @RequestBody(required = false) byte[] raw,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestParam MultiValueMap<String, String> query) {
        if (!isJsonContentType(contentType) || !query.isEmpty()) throw invalid();
        var identity = sessions.requireForWrite(authorization, origin, host);
        if (raw == null || raw.length == 0) throw invalid();
        if (raw.length > 64 * 1024) throw new MiniappDemoTextException(MiniappDemoTextException.Reason.INPUT_TOO_LARGE);
        JsonNode body;
        try (JsonParser parser = mapper.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            body = mapper.readTree(parser);
            if (parser.nextToken() != null) throw invalid();
        } catch (IOException ignored) { throw invalid(); }
        return noStore(CommonResult.success(texts.create(identity, body, key)));
    }
    @ExceptionHandler(MiniappDemoTextException.class)
    public ResponseEntity<CommonResult<Object>> textError(MiniappDemoTextException failure) {
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
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) { return textError(invalid()); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return fileError(new MiniappDemoFileException(MiniappDemoFileException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Object>> safeError(Exception ignored) {
        // Local handler deliberately avoids framework error logging, which persists request bodies.
        return textError(new MiniappDemoTextException(MiniappDemoTextException.Reason.RENDER_UNAVAILABLE));
    }
    private static MiniappDemoTextException invalid() { return new MiniappDemoTextException(MiniappDemoTextException.Reason.INVALID_INPUT); }
    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return "application".equals(parsed.getType()) && "json".equals(parsed.getSubtype());
        } catch (InvalidMediaTypeException ignored) {
            return false;
        }
    }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
    private static ResponseEntity<CommonResult<Object>> error(int status, int code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(code, message));
    }
}
