package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.order.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/print/orders")
public class PrintOrderController {
    private final PrintOrderService orders;
    public PrintOrderController(PrintOrderService orders){this.orders=orders;}
    @PostMapping
    @PreAuthorize("@ss.hasPermission('print:order:create')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-orders",operateName="create")
    public CommonResult<PrintOrderView> create(@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@RequestParam MultiValueMap<String,String> query){
        requireEmpty(query);return CommonResult.success(orders.create(body,key));
    }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('print:order:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-orders",operateName="get")
    public CommonResult<PrintOrderView> get(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){
        requireEmpty(query);return CommonResult.success(orders.get(id));
    }
    @GetMapping
    @PreAuthorize("@ss.hasPermission('print:order:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-orders",operateName="list")
    public CommonResult<PrintOrderPage<PrintOrderSummary>> list(@RequestParam MultiValueMap<String,String> query){
        int[] page=pagination(query);return CommonResult.success(orders.list(page[0],page[1]));
    }
    @GetMapping("/{id}/events")
    @PreAuthorize("@ss.hasPermission('print:order:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-orders",operateName="events")
    public CommonResult<PrintOrderPage<PrintOrderEventView>> events(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){
        int[] page=pagination(query);return CommonResult.success(orders.events(id,page[0],page[1]));
    }
    private static int[] pagination(MultiValueMap<String,String> query){
        if(!Set.of("pageNo","pageSize").containsAll(query.keySet()))throw invalid();
        return new int[]{number(query,"pageNo",1,1_000_000),number(query,"pageSize",20,100)};
    }
    private static int number(MultiValueMap<String,String> query,String key,int fallback,int maximum){
        var values=query.get(key);if(values==null)return fallback;
        if(values.size()!=1 || values.get(0)==null || !values.get(0).matches("[1-9][0-9]{0,6}"))throw invalid();
        int value=Integer.parseInt(values.get(0));if(value>maximum)throw invalid();return value;
    }
    private static void requireEmpty(MultiValueMap<String,String> query){if(!query.isEmpty())throw invalid();}
    private static PrintOrderException invalid(){return new PrintOrderException(PrintOrderException.Reason.INVALID_INPUT);}
    @ExceptionHandler(PrintOrderException.class)
    public ResponseEntity<CommonResult<Object>> orderError(PrintOrderException failure){
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored){
        return orderError(new PrintOrderException(PrintOrderException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored){return orderError(invalid());}
}
