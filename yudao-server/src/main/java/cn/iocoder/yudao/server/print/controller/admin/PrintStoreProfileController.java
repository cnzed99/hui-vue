package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.store.profile.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/print/store/current")
public class PrintStoreProfileController {
    private final PrintStoreProfileService profiles;
    public PrintStoreProfileController(PrintStoreProfileService profiles) { this.profiles=profiles; }
    @GetMapping
    @PreAuthorize("@ss.hasPermission('print:store:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-store",operateName="query")
    public CommonResult<PrintStoreProfileView> get(@RequestParam MultiValueMap<String,String> query) {
        requireEmpty(query); return CommonResult.success(profiles.get());
    }
    @PutMapping
    @PreAuthorize("@ss.hasPermission('print:store:update')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-store",operateName="update")
    public CommonResult<PrintStoreProfileView> put(@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query) {
        requireEmpty(query); return CommonResult.success(profiles.put(body));
    }
    private static void requireEmpty(MultiValueMap<String,String> query) {
        if (!query.isEmpty()) throw new PrintStoreProfileException(PrintStoreProfileException.Reason.INVALID_INPUT);
    }
    @ExceptionHandler(PrintStoreProfileException.class)
    public ResponseEntity<CommonResult<Object>> profileError(PrintStoreProfileException failure) {
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return profileError(new PrintStoreProfileException(PrintStoreProfileException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) {
        return profileError(new PrintStoreProfileException(PrintStoreProfileException.Reason.INVALID_INPUT));
    }
}
