package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.miniappdemo.admin.MiniappDemoAdminOrderPage;
import cn.iocoder.yudao.server.print.miniappdemo.admin.MiniappDemoAdminOrderService;
import cn.iocoder.yudao.server.print.miniappdemo.admin.MiniappDemoAdminOrderSummary;
import cn.iocoder.yudao.server.print.miniappdemo.admin.MiniappDemoAdminOrderView;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintActionView;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskException;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskService;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/print/miniapp-demo")
public class PrintMiniappDemoAdminController {
    private final MiniappDemoAdminOrderService orders;
    private final MiniappDemoPrintTaskService tasks;

    public PrintMiniappDemoAdminController(MiniappDemoAdminOrderService orders,
            MiniappDemoPrintTaskService tasks) {
        this.orders = orders;
        this.tasks = tasks;
    }

    @GetMapping("/orders")
    @PreAuthorize("@ss.hasPermission('print:order:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-orders", operateName = "list")
    public CommonResult<MiniappDemoAdminOrderPage<MiniappDemoAdminOrderSummary>> list(
            @RequestParam MultiValueMap<String, String> query) {
        if (!Set.of("pageNo", "pageSize").containsAll(query.keySet())) throw invalid();
        return CommonResult.success(orders.list(number(query, "pageNo", 1, 1_000_000),
                number(query, "pageSize", 20, 100)));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("@ss.hasPermission('print:order:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-orders", operateName = "get")
    public CommonResult<MiniappDemoAdminOrderView> get(@PathVariable String id,
            @RequestParam MultiValueMap<String, String> query) {
        requireEmpty(query);
        return CommonResult.success(orders.get(id));
    }

    @PostMapping(value = "/orders/{id}/print-task", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@ss.hasPermission('print:order:process')")
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-processing", operateName = "create-task")
    public CommonResult<MiniappDemoPrintTaskView> createTask(@PathVariable String id,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam MultiValueMap<String, String> query) {
        requireEmpty(query);
        return CommonResult.success(tasks.create(id, body, key));
    }

    @PostMapping(value = "/print-tasks/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@ss.hasPermission('print:order:process')")
    @ApiAccessLog(requestEnable = false, responseEnable = false,
            operateModule = "print-miniapp-demo-processing", operateName = "task-action")
    public CommonResult<MiniappDemoPrintActionView> act(@PathVariable String id,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam MultiValueMap<String, String> query) {
        requireEmpty(query);
        return CommonResult.success(tasks.act(id, body, key));
    }

    private static int number(MultiValueMap<String, String> query, String key, int fallback, int maximum) {
        var values = query.get(key);
        if (values == null) return fallback;
        if (values.size() != 1 || values.get(0) == null || !values.get(0).matches("[1-9][0-9]{0,6}")) throw invalid();
        int value = Integer.parseInt(values.get(0));
        if (value > maximum) throw invalid();
        return value;
    }
    private static void requireEmpty(MultiValueMap<String, String> query) { if (!query.isEmpty()) throw invalid(); }
    private static MiniappDemoPrintTaskException invalid() {
        return new MiniappDemoPrintTaskException(MiniappDemoPrintTaskException.Reason.INVALID_INPUT);
    }

    @ExceptionHandler(MiniappDemoPrintTaskException.class)
    public ResponseEntity<CommonResult<Object>> taskError(MiniappDemoPrintTaskException failure) {
        return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.code(), failure.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storageError(RuntimeException ignored) {
        return taskError(new MiniappDemoPrintTaskException(MiniappDemoPrintTaskException.Reason.STORAGE_UNAVAILABLE));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> inputError(HttpMessageNotReadableException ignored) {
        return taskError(invalid());
    }
}
