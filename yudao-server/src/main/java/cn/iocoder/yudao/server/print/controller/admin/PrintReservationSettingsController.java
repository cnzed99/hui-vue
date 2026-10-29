package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.reservation.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/print/store/reservation-settings")
public class PrintReservationSettingsController {
    private final PrintReservationSettingsService settings;
    public PrintReservationSettingsController(PrintReservationSettingsService settings) { this.settings=settings; }
    @GetMapping @PreAuthorize("@ss.hasPermission('print:store:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-reservation-settings",operateName="query")
    public CommonResult<PrintReservationSettings> get(@RequestParam MultiValueMap<String,String> query) { empty(query); return CommonResult.success(settings.get()); }
    @PutMapping @PreAuthorize("@ss.hasPermission('print:store:update')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-reservation-settings",operateName="update")
    public CommonResult<PrintReservationSettings> put(@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query) { empty(query); return CommonResult.success(settings.put(body)); }
    private static void empty(MultiValueMap<String,String> query) { if (!query.isEmpty()) throw new PrintReservationSettingsException(PrintReservationSettingsException.Reason.INVALID_INPUT); }
    @ExceptionHandler(PrintReservationSettingsException.class)
    public ResponseEntity<CommonResult<Object>> error(PrintReservationSettingsException e) { return ResponseEntity.status(e.reason.status).body(CommonResult.error(e.reason.code,e.getMessage())); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storage(RuntimeException ignored) { return error(new PrintReservationSettingsException(PrintReservationSettingsException.Reason.STORAGE_UNAVAILABLE)); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> input(HttpMessageNotReadableException ignored) { return error(new PrintReservationSettingsException(PrintReservationSettingsException.Reason.INVALID_INPUT)); }
}
