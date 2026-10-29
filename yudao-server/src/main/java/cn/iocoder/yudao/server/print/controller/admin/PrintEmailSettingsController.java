package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.email.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/print/store/email-settings")
public class PrintEmailSettingsController {
    private final PrintEmailSettingsService settings;
    public PrintEmailSettingsController(PrintEmailSettingsService settings) { this.settings = settings; }

    @GetMapping @PreAuthorize("@ss.hasPermission('print:store:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-email-settings", operateName = "query")
    public CommonResult<PrintEmailSettings> get(@RequestParam MultiValueMap<String, String> query) {
        empty(query); return CommonResult.success(settings.get());
    }

    @PutMapping @PreAuthorize("@ss.hasPermission('print:store:update')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-email-settings", operateName = "update")
    public CommonResult<PrintEmailSettings> put(@RequestBody JsonNode body,
            @RequestParam MultiValueMap<String, String> query) {
        empty(query); return CommonResult.success(settings.put(body));
    }

    private static void empty(MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw new PrintEmailSettingsException(PrintEmailSettingsException.Reason.INVALID_INPUT);
    }

    @ExceptionHandler(PrintEmailSettingsException.class)
    public ResponseEntity<CommonResult<Object>> error(PrintEmailSettingsException e) {
        return ResponseEntity.status(e.reason.status).body(CommonResult.error(e.reason.code, e.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storage(RuntimeException ignored) {
        return error(new PrintEmailSettingsException(PrintEmailSettingsException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> input(HttpMessageNotReadableException ignored) {
        return error(new PrintEmailSettingsException(PrintEmailSettingsException.Reason.INVALID_INPUT));
    }
}
