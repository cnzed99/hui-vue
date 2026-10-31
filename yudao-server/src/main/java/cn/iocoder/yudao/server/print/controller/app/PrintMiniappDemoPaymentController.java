package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoPaymentException;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoPaymentService;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoPaymentView;
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
public class PrintMiniappDemoPaymentController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoPaymentService payments;

    public PrintMiniappDemoPaymentController(MiniappDemoSessionService sessions, MiniappDemoPaymentService payments) {
        this.sessions = sessions;
        this.payments = payments;
    }

    @PostMapping(value = "/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-payments", operateName = "create")
    public ResponseEntity<CommonResult<MiniappDemoPaymentView>> create(
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid();
        MiniappDemoIdentity identity = sessions.requireForWrite(authorization, origin, host);
        return noStore(CommonResult.success(payments.create(identity, body, key)));
    }

    @GetMapping("/payment")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-payments", operateName = "get")
    public ResponseEntity<CommonResult<MiniappDemoPaymentView>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 1 || query.get("orderId") == null || query.get("orderId").size() != 1) throw invalid();
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(payments.get(identity, query.getFirst("orderId"))));
    }

    private static MiniappDemoPaymentException invalid() {
        return new MiniappDemoPaymentException(MiniappDemoPaymentException.Reason.INVALID_INPUT);
    }

    @ExceptionHandler(MiniappDemoPaymentException.class)
    public ResponseEntity<CommonResult<Object>> paymentError(MiniappDemoPaymentException failure) {
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
        return paymentError(new MiniappDemoPaymentException(MiniappDemoPaymentException.Reason.STORAGE_UNAVAILABLE));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) {
        return paymentError(invalid());
    }

    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
