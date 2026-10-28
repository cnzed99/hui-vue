package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.library.settings.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/print/library/settings")
public class PrintLibrarySettingsController {
    private final PrintLibrarySettingsService settings;
    public PrintLibrarySettingsController(PrintLibrarySettingsService settings){this.settings=settings;}
    @GetMapping @PreAuthorize("@ss.hasPermission('print:library-settings:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-settings",operateName="query")
    public CommonResult<PrintLibrarySettings> get(@RequestParam MultiValueMap<String,String> query){empty(query);return CommonResult.success(settings.get());}
    @PutMapping @PreAuthorize("@ss.hasPermission('print:library-settings:update')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-settings",operateName="update")
    public CommonResult<PrintLibrarySettings> put(@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){empty(query);return CommonResult.success(settings.put(body));}
    private static void empty(MultiValueMap<String,String> query){if(!query.isEmpty())throw new PrintLibrarySettingsException(PrintLibrarySettingsException.Reason.INVALID_INPUT);}
    @ExceptionHandler(PrintLibrarySettingsException.class)
    public ResponseEntity<CommonResult<Object>> error(PrintLibrarySettingsException e){return ResponseEntity.status(e.reason.status).body(CommonResult.error(e.reason.code,e.getMessage()));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storage(RuntimeException ignored){return error(new PrintLibrarySettingsException(PrintLibrarySettingsException.Reason.STORAGE_UNAVAILABLE));}
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> input(HttpMessageNotReadableException ignored){return error(new PrintLibrarySettingsException(PrintLibrarySettingsException.Reason.INVALID_INPUT));}
}
