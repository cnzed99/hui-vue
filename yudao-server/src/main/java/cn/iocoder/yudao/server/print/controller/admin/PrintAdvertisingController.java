package cn.iocoder.yudao.server.print.controller.admin;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.advertising.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/print/advertising")
@ConditionalOnProperty(prefix="print.advertising",name="enabled",havingValue="true")
public class PrintAdvertisingController {
    private final PrintAdvertisingService service;
    public PrintAdvertisingController(PrintAdvertisingService service){this.service=service;}
    @GetMapping("/{position}") @PreAuthorize("@ss.hasPermission('print:advertising:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-advertising",operateName="query")
    public CommonResult<PrintAdvertisingDraft> get(@PathVariable String position,@RequestParam MultiValueMap<String,String> query){empty(query);return CommonResult.success(service.get(PrintAdPosition.parse(position)));}
    @PutMapping("/{position}") @PreAuthorize("@ss.hasPermission('print:advertising:update')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-advertising",operateName="update-draft")
    public CommonResult<PrintAdvertisingDraft> put(@PathVariable String position,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){empty(query);return CommonResult.success(service.put(PrintAdPosition.parse(position),body));}
    private static void empty(MultiValueMap<String,String> query){if(!query.isEmpty())throw new PrintAdvertisingException(PrintAdvertisingException.Reason.INVALID);}
    @ExceptionHandler(PrintAdvertisingException.class)
    public ResponseEntity<CommonResult<Object>> error(PrintAdvertisingException e){return ResponseEntity.status(e.reason.status).body(CommonResult.error(e.reason.code,e.getMessage()));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storage(RuntimeException ignored){return error(new PrintAdvertisingException(PrintAdvertisingException.Reason.STORAGE));}
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> input(HttpMessageNotReadableException ignored){return error(new PrintAdvertisingException(PrintAdvertisingException.Reason.INVALID));}
}
