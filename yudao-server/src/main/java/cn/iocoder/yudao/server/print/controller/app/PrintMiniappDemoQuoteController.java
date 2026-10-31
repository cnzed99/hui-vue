package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteException;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteService;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoQuoteController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoQuoteService quotes;

    public PrintMiniappDemoQuoteController(MiniappDemoSessionService sessions, MiniappDemoQuoteService quotes) {
        this.sessions = sessions;
        this.quotes = quotes;
    }

    @PostMapping(value = "/quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-quotes", operateName = "create")
    public ResponseEntity<CommonResult<MiniappDemoQuoteView>> create(
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid();
        MiniappDemoIdentity identity = sessions.requireForWrite(authorization, origin, host);
        return noStore(CommonResult.success(quotes.create(identity, body, key)));
    }

    @GetMapping("/quote")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-quotes", operateName = "get")
    public ResponseEntity<CommonResult<MiniappDemoQuoteView>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 1 || query.get("id") == null || query.get("id").size() != 1) throw invalid();
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(quotes.get(identity, query.getFirst("id"))));
    }

    private static MiniappDemoQuoteException invalid() {
        return new MiniappDemoQuoteException(MiniappDemoQuoteException.Reason.INVALID_INPUT);
    }

    @ExceptionHandler(MiniappDemoQuoteException.class)
    public ResponseEntity<CommonResult<Object>> quoteError(MiniappDemoQuoteException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return quoteError(new MiniappDemoQuoteException(MiniappDemoQuoteException.Reason.STORAGE_UNAVAILABLE));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) {
        return quoteError(invalid());
    }

    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
