package cn.iocoder.yudao.server.print.controller.admin;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.quote.PrintQuoteException;
import cn.iocoder.yudao.server.print.quote.PrintQuoteService;
import cn.iocoder.yudao.server.print.quote.PrintQuoteView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/print/quotes")
public class PrintQuoteController {
    private final PrintQuoteService quotes;
    public PrintQuoteController(PrintQuoteService quotes) { this.quotes=quotes; }
    @PostMapping
    @PreAuthorize("@ss.hasPermission('print:quote:create')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-quotes",operateName="create")
    public CommonResult<PrintQuoteView> create(@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return CommonResult.success(quotes.create(body,key));
    }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('print:quote:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-quotes",operateName="get")
    public CommonResult<PrintQuoteView> get(@PathVariable String id) { return CommonResult.success(quotes.get(id)); }
    @ExceptionHandler(PrintQuoteException.class)
    public ResponseEntity<CommonResult<Object>> quoteError(PrintQuoteException failure) {
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return quoteError(new PrintQuoteException(PrintQuoteException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) {
        return quoteError(new PrintQuoteException(PrintQuoteException.Reason.INVALID_INPUT));
    }
}
