package cn.iocoder.yudao.server.print.controller.admin;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.order.PrintOrderException;
import cn.iocoder.yudao.server.print.payment.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/print/orders")
public class PrintPaymentController {
    private final PrintPaymentService payments;
    public PrintPaymentController(PrintPaymentService payments){this.payments=payments;}
    @PostMapping("/{id}/simulate-payment")
    @PreAuthorize("@ss.hasPermission('print:payment:simulate')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-payments",operateName="simulate")
    public CommonResult<PrintPaymentView> simulate(@PathVariable String id,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@RequestParam MultiValueMap<String,String> query){
        requireEmpty(query);return CommonResult.success(payments.simulate(id,body,key));
    }
    @GetMapping("/{id}/payment")
    @PreAuthorize("@ss.hasPermission('print:order:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-payments",operateName="get")
    public CommonResult<PrintPaymentView> get(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){
        requireEmpty(query);return CommonResult.success(payments.get(id));
    }
    private static void requireEmpty(MultiValueMap<String,String> query){if(!query.isEmpty())throw new PrintPaymentException(PrintPaymentException.Reason.INVALID_INPUT);}
    @ExceptionHandler(PrintPaymentException.class)
    public ResponseEntity<CommonResult<Object>> paymentError(PrintPaymentException failure){
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler(PrintOrderException.class)
    public ResponseEntity<CommonResult<Object>> orderError(PrintOrderException failure){
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored){
        return paymentError(new PrintPaymentException(PrintPaymentException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored){
        return paymentError(new PrintPaymentException(PrintPaymentException.Reason.INVALID_INPUT));
    }
}
