package cn.iocoder.yudao.server.print.controller.admin;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.printer.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/print/directory/printers")
public class PrintPrinterDirectoryController {
    private final PrintPrinterDirectoryService service;
    public PrintPrinterDirectoryController(PrintPrinterDirectoryService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermission('print:printer-directory:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-printer-directory")
    public CommonResult<PrintPrinterDirectoryModel.Page> list(@RequestParam MultiValueMap<String,String> q){return CommonResult.success(service.list(q));}
    @ExceptionHandler(PrintPrinterDirectoryException.class)
    public ResponseEntity<CommonResult<Object>> input(PrintPrinterDirectoryException e){return ResponseEntity.status(e.status).body(CommonResult.error(e.code,e.getMessage()));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<CommonResult<Object>> storage(DataAccessException ignored){return ResponseEntity.status(503).body(CommonResult.error(1510100002,"打印机目录数据源尚未启用或暂时不可用"));}
}
