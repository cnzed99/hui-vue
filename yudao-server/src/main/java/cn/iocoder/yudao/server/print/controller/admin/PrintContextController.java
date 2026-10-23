package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.scope.PrintContext;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Own context only: no caller-supplied tenant, store or user override. */
@RestController
@RequestMapping("/print/context")
public class PrintContextController {
    private final PrintScopeService scopeService;

    public PrintContextController(PrintScopeService scopeService) {
        this.scopeService = scopeService;
    }

    @GetMapping
    public CommonResult<PrintContext> getCurrentContext() {
        return CommonResult.success(scopeService.currentContext());
    }
}
