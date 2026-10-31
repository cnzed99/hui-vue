package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
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
public class PrintMiniappDemoSessionController {
    private final MiniappDemoSessionService sessions;
    public PrintMiniappDemoSessionController(MiniappDemoSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/sessions")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-session", operateName = "issue")
    public ResponseEntity<CommonResult<MiniappDemoSessionService.IssuedSession>> issue(
            @RequestBody(required = false) byte[] body, @RequestParam MultiValueMap<String, String> query,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host) {
        if ((body != null && body.length != 0) || !query.isEmpty()) throw invalid();
        return noStore(CommonResult.success(sessions.issue(origin, host)));
    }

    @GetMapping("/session")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-demo-session", operateName = "get")
    public ResponseEntity<CommonResult<SessionView>> get(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam MultiValueMap<String, String> query,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Host", required = false) String host) {
        if (!query.isEmpty()) throw invalid();
        MiniappDemoIdentity identity = sessions.require(authorization, origin, host);
        return noStore(CommonResult.success(new SessionView(identity.principalId(), identity.expiresAtMs(), "MINIAPP_DEMO")));
    }

    private static MiniappDemoSessionException invalid() {
        return new MiniappDemoSessionException(MiniappDemoSessionException.Reason.INVALID_INPUT);
    }

    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(CommonResult.error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return sessionError(new MiniappDemoSessionException(MiniappDemoSessionException.Reason.STORAGE_UNAVAILABLE));
    }

    public record SessionView(String principalId, long expiresAtMs, String actorType) {}

    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
