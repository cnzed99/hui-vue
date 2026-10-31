package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskException;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskService;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskView;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/print/miniapp/demo")
@TenantIgnore
public class PrintMiniappDemoPrintResultController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoPrintTaskService tasks;

    public PrintMiniappDemoPrintResultController(MiniappDemoSessionService sessions,
            MiniappDemoPrintTaskService tasks) {
        this.sessions = sessions;
        this.tasks = tasks;
    }

    @GetMapping("/print-result")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-processing", operateName = "result")
    public ResponseEntity<CommonResult<MiniappDemoPrintTaskView>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 1 || query.get("orderId") == null || query.get("orderId").size() != 1) throw invalid();
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(tasks.getForOwner(identity, query.getFirst("orderId"))));
    }

    private static MiniappDemoPrintTaskException invalid() {
        return new MiniappDemoPrintTaskException(MiniappDemoPrintTaskException.Reason.INVALID_INPUT);
    }
    @ExceptionHandler(MiniappDemoPrintTaskException.class)
    public ResponseEntity<CommonResult<Object>> taskError(MiniappDemoPrintTaskException failure) {
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
        return taskError(new MiniappDemoPrintTaskException(MiniappDemoPrintTaskException.Reason.STORAGE_UNAVAILABLE));
    }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
