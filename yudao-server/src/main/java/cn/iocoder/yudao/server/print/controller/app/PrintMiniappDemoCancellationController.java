package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionService;
import cn.iocoder.yudao.server.print.miniappdemo.cancellation.MiniappDemoCancellationException;
import cn.iocoder.yudao.server.print.miniappdemo.cancellation.MiniappDemoCancellationService;
import cn.iocoder.yudao.server.print.miniappdemo.cancellation.MiniappDemoCancellationView;
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
public class PrintMiniappDemoCancellationController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoCancellationService cancellations;
    public PrintMiniappDemoCancellationController(MiniappDemoSessionService sessions, MiniappDemoCancellationService cancellations) {
        this.sessions=sessions; this.cancellations=cancellations;
    }
    @GetMapping("/cancellation")
    @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-cancellation",operateName="get")
    public ResponseEntity<CommonResult<MiniappDemoCancellationView>> get(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="Origin",required=false) String origin,
            @RequestHeader(value="Referer",required=false) String referer,
            @RequestHeader(value="Host",required=false) String host,
            @RequestParam MultiValueMap<String,String> query) {
        if(query.size()!=1 || query.get("orderId")==null || query.get("orderId").size()!=1) throw invalid();
        return noStore(CommonResult.success(cancellations.get(sessions.require(authorization,origin,host),query.getFirst("orderId"))));
    }
    @PostMapping(value="/cancellations",consumes=MediaType.APPLICATION_JSON_VALUE)
    @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-cancellation",operateName="create")
    public ResponseEntity<CommonResult<MiniappDemoCancellationView.ActionView>> create(
            @RequestBody(required=false) JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="Origin",required=false) String origin,
            @RequestHeader(value="Referer",required=false) String referer,
            @RequestHeader(value="Host",required=false) String host,
            @RequestHeader(value="Content-Type",required=false) String contentType,
            @RequestParam MultiValueMap<String,String> query) {
        if(!query.isEmpty() || contentType==null || !MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(contentType.split(";",2)[0].trim())) throw invalid();
        return noStore(CommonResult.success(cancellations.create(sessions.requireForWrite(authorization,origin,referer,host),body,key)));
    }
    @ExceptionHandler(MiniappDemoCancellationException.class)
    public ResponseEntity<CommonResult<Object>> error(MiniappDemoCancellationException failure) {
        CommonResult<Object> body=CommonResult.error(failure.code(),failure.getMessage());
        body.setData(failure.data());
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache").body(body);
    }
    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException failure) { return error(failure.status().value(),failure.code(),failure.getMessage()); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) { return error(new MiniappDemoCancellationException(MiniappDemoCancellationException.Reason.STORAGE_UNAVAILABLE)); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) { return error(invalid()); }
    private static MiniappDemoCancellationException invalid() { return new MiniappDemoCancellationException(MiniappDemoCancellationException.Reason.INVALID_INPUT); }
    private static ResponseEntity<CommonResult<Object>> error(int status,int code,String message) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache").body(CommonResult.error(code,message)); }
    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache").body(body); }
}
