package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.library.MiniappDemoLibraryCatalog;
import cn.iocoder.yudao.server.print.miniappdemo.library.MiniappDemoLibraryException;
import cn.iocoder.yudao.server.print.miniappdemo.library.MiniappDemoLibraryFileService;
import cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
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
import static cn.iocoder.yudao.server.print.miniappdemo.library.MiniappDemoLibraryException.Reason.*;

/** Public catalog is fixed original demo content; private materialization requires a demo actor. */
@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoLibraryController {
    private final MiniappDemoLibraryCatalog catalog;
    private final MiniappDemoLibraryFileService libraryFiles;
    private final MiniappDemoSessionService sessions;
    private final ObjectMapper mapper;
    public PrintMiniappDemoLibraryController(MiniappDemoLibraryCatalog catalog,MiniappDemoLibraryFileService libraryFiles,
            MiniappDemoSessionService sessions,ObjectMapper mapper) {
        this.catalog=catalog; this.libraryFiles=libraryFiles; this.sessions=sessions; this.mapper=mapper;
    }
    @GetMapping("/library")
    @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-library",operateName="list")
    public ResponseEntity<CommonResult<MiniappDemoLibraryCatalog.Page>> list(@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        requireGet(request);
        return noStore(CommonResult.success(catalog.list(query)));
    }
    @GetMapping("/library-entry")
    @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-library",operateName="detail")
    public ResponseEntity<CommonResult<MiniappDemoLibraryCatalog.Detail>> detail(@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        requireGet(request);
        return noStore(CommonResult.success(catalog.detail(query)));
    }
    @PostMapping("/library-files")
    @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-library",operateName="create")
    public ResponseEntity<CommonResult<MiniappDemoFileMetadata>> create(
            @RequestBody(required=false) byte[] raw,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="Origin",required=false) String origin,
            @RequestHeader(value="Referer",required=false) String referer,
            @RequestHeader(value="Host",required=false) String host,
            @RequestHeader(value=HttpHeaders.CONTENT_TYPE,required=false) String contentType,
            @RequestParam MultiValueMap<String,String> query) {
        if (!isJson(contentType) || !query.isEmpty()) throw invalid();
        var identity=sessions.requireForWrite(authorization,origin,referer,host);
        if (raw==null || raw.length==0) throw invalid();
        if (raw.length>2048) throw new MiniappDemoLibraryException(TOO_LARGE);
        JsonNode body;
        try (JsonParser parser=mapper.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            body=mapper.readTree(parser);
            if (parser.nextToken()!=null) throw invalid();
        } catch (IOException ignored) { throw invalid(); }
        return noStore(CommonResult.success(libraryFiles.create(identity,body,key)));
    }
    @ExceptionHandler(MiniappDemoLibraryException.class)
    public ResponseEntity<CommonResult<Object>> libraryError(MiniappDemoLibraryException failure) { return error(failure.status().value(),failure.code(),failure.getMessage()); }
    @ExceptionHandler(MiniappDemoTextException.class)
    public ResponseEntity<CommonResult<Object>> textError(MiniappDemoTextException failure) { return error(failure.status().value(),failure.code(),failure.getMessage()); }
    @ExceptionHandler(MiniappDemoFileException.class)
    public ResponseEntity<CommonResult<Object>> fileError(MiniappDemoFileException failure) { return error(failure.status().value(),failure.code(),failure.getMessage()); }
    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException failure) { return error(failure.status().value(),failure.code(),failure.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) { return libraryError(invalid()); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) { return fileError(new MiniappDemoFileException(MiniappDemoFileException.Reason.STORAGE_UNAVAILABLE)); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Object>> safeError(Exception ignored) {
        // Do not delegate to framework exception logging that persists request data.
        return fileError(new MiniappDemoFileException(MiniappDemoFileException.Reason.STORAGE_UNAVAILABLE));
    }
    private static void requireGet(HttpServletRequest request) { if (!"GET".equals(request.getMethod())) throw invalid(); }
    private static MiniappDemoLibraryException invalid() { return new MiniappDemoLibraryException(INVALID_INPUT); }
    private static boolean isJson(String contentType) {
        if (contentType==null) return false;
        try { MediaType type=MediaType.parseMediaType(contentType); return "application".equals(type.getType()) && "json".equals(type.getSubtype()); }
        catch (InvalidMediaTypeException ignored) { return false; }
    }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache").body(body);
    }
    private static ResponseEntity<CommonResult<Object>> error(int status,int code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache").body(CommonResult.error(code,message));
    }
}
