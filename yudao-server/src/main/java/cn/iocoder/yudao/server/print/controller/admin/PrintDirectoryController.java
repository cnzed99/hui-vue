package cn.iocoder.yudao.server.print.controller.admin;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.directory.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.server.print.directory.PrintDirectoryModel.*;
/** Only GET directory endpoints. New permission definitions/grants remain unactivated drafts. */
@RestController @RequestMapping("/print/directory")
public class PrintDirectoryController {
    private final PrintDirectoryService service;
    public PrintDirectoryController(PrintDirectoryService service){this.service=service;}
    @GetMapping("/my-products") @PreAuthorize("@ss.hasPermission('print:product:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-directory")
    public CommonResult<Page> my(@RequestParam MultiValueMap<String,String> q){return CommonResult.success(service.list(Kind.MY,q));}
    @GetMapping("/platform-products") @PreAuthorize("@ss.hasPermission('print:platform-product:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-directory")
    public CommonResult<Page> platform(@RequestParam MultiValueMap<String,String> q){return CommonResult.success(service.list(Kind.PLATFORM,q));}
    @GetMapping("/library") @PreAuthorize("@ss.hasPermission('print:library-entry:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-directory")
    public CommonResult<Page> library(@RequestParam MultiValueMap<String,String> q){return CommonResult.success(service.list(Kind.LIBRARY,q));}
    @GetMapping("/terminals") @PreAuthorize("@ss.hasPermission('print:terminal-directory:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-directory")
    public CommonResult<Page> terminals(@RequestParam MultiValueMap<String,String> q){return CommonResult.success(service.list(Kind.TERMINAL,q));}
    @ExceptionHandler(PrintDirectoryException.class)
    public ResponseEntity<CommonResult<Object>> input(PrintDirectoryException e){return ResponseEntity.status(e.status).body(CommonResult.error(e.code,e.getMessage()));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<CommonResult<Object>> storage(DataAccessException ignored){return ResponseEntity.status(503).body(CommonResult.error(1510090002,"目录数据源尚未启用或暂时不可用"));}
}
