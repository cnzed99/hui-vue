package cn.iocoder.yudao.server.print.controller.admin;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.miniappdemo.photo.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/print/miniapp-demo/photo-price")
public class PrintMiniappDemoPhotoPriceController {
    private final MiniappDemoPhotoPriceService prices;
    public PrintMiniappDemoPhotoPriceController(MiniappDemoPhotoPriceService prices){this.prices=prices;}
    @GetMapping @PreAuthorize("@ss.hasPermission('print:photo-price:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-photo-price",operateName="query")
    public ResponseEntity<CommonResult<MiniappDemoPhotoPriceService.View>> get(@RequestParam MultiValueMap<String,String> query){empty(query);return ok(prices.get());}
    @PutMapping(consumes=MediaType.APPLICATION_JSON_VALUE) @PreAuthorize("@ss.hasPermission('print:photo-price:update')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-photo-price",operateName="update")
    public ResponseEntity<CommonResult<MiniappDemoPhotoPriceService.View>> put(@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){empty(query);return ok(prices.update(body));}
    private static void empty(MultiValueMap<String,String> query){if(!query.isEmpty())throw new MiniappDemoPhotoException(400,43100,"Query parameters not allowed");}
    private static <T> ResponseEntity<CommonResult<T>> ok(T data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResult.success(data));}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<CommonResult<Object>> forbidden(org.springframework.security.access.AccessDeniedException e){
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(CommonResult.error(403,"Photo price permission denied"));
    }
    @ExceptionHandler(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
    public ResponseEntity<CommonResult<Object>> scopeError(cn.iocoder.yudao.framework.common.exception.ServiceException e){
        int status=e.getCode()==cn.iocoder.yudao.server.print.scope.PrintScopeErrorCodes.LOGIN_REQUIRED.getCode()?401:403;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(CommonResult.error(e.getCode(),e.getMessage()));
    }
    @ExceptionHandler(MiniappDemoPhotoException.class) public ResponseEntity<CommonResult<Object>> error(MiniappDemoPhotoException e){return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(CommonResult.error(e.code(),e.getMessage()));}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<CommonResult<Object>> unavailable(RuntimeException e){return error(new MiniappDemoPhotoException(503,43102,"Photo price configuration unavailable"));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> invalid(RuntimeException e){return error(new MiniappDemoPhotoException(400,43100,"Invalid photo price input"));}
}
