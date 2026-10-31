package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderException;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderPage;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderService;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderView;
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
public class PrintMiniappDemoOrderController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoOrderService orders;

    public PrintMiniappDemoOrderController(MiniappDemoSessionService sessions, MiniappDemoOrderService orders) {
        this.sessions = sessions;
        this.orders = orders;
    }

    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-orders", operateName = "create")
    public ResponseEntity<CommonResult<MiniappDemoOrderView>> create(
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw invalid();
        MiniappDemoIdentity identity = sessions.requireForWrite(authorization, origin, host);
        return noStore(CommonResult.success(orders.create(identity, body, key)));
    }

    @GetMapping("/orders")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-orders", operateName = "page")
    public ResponseEntity<CommonResult<MiniappDemoOrderPage>> page(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 2 || query.get("pageNo") == null || query.get("pageNo").size() != 1
                || query.get("pageSize") == null || query.get("pageSize").size() != 1) throw invalid();
        int pageNo = pageValue(query.getFirst("pageNo"), 10_000);
        int pageSize = pageValue(query.getFirst("pageSize"), 20);
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(orders.page(identity, pageNo, pageSize)));
    }

    @GetMapping("/order")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-orders", operateName = "get")
    public ResponseEntity<CommonResult<MiniappDemoOrderView>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 1 || query.get("id") == null || query.get("id").size() != 1) throw invalid();
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(orders.get(identity, query.getFirst("id"))));
    }

    private static MiniappDemoOrderException invalid() {
        return new MiniappDemoOrderException(MiniappDemoOrderException.Reason.INVALID_INPUT);
    }

    private static int pageValue(String value, int maximum) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}")) throw invalid();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > maximum) throw invalid();
            return parsed;
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
    }

    @ExceptionHandler(MiniappDemoOrderException.class)
    public ResponseEntity<CommonResult<Object>> orderError(MiniappDemoOrderException failure) {
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
        return orderError(new MiniappDemoOrderException(MiniappDemoOrderException.Reason.STORAGE_UNAVAILABLE));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) {
        return orderError(invalid());
    }

    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
