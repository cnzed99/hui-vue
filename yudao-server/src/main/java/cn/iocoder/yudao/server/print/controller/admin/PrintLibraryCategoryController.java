package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.library.category.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import static cn.iocoder.yudao.server.print.library.category.PrintLibraryCategoryException.Reason.*;

@RestController @RequestMapping("/print/library/categories")
public class PrintLibraryCategoryController {
    private final PrintLibraryCategoryService service;
    public PrintLibraryCategoryController(PrintLibraryCategoryService service){this.service=service;}
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-category")
    @GetMapping @PreAuthorize("@ss.hasPermission('print:library-category:query')")
    public CommonResult<PrintLibraryCategoryService.Page> list(@RequestParam MultiValueMap<String,String> q){
        if(!Set.of("page","pageSize").containsAll(q.keySet()))throw new PrintLibraryCategoryException(INVALID_INPUT);
        return CommonResult.success(service.list(number(q,"page",1),number(q,"pageSize",20)));
    }
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-category")
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermission('print:library-category:query')")
    public CommonResult<PrintLibraryCategory> get(@PathVariable String id,@RequestParam MultiValueMap<String,String> q){empty(q);return CommonResult.success(service.get(id));}
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-category")
    @PostMapping @PreAuthorize("@ss.hasPermission('print:library-category:create')")
    public CommonResult<PrintLibraryCategory> create(@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> q){empty(q);return CommonResult.success(service.create(body));}
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-category")
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermission('print:library-category:update')")
    public CommonResult<PrintLibraryCategory> update(@PathVariable String id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> q){empty(q);return CommonResult.success(service.update(id,body));}
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-library-category")
    @DeleteMapping("/{id}") @PreAuthorize("@ss.hasPermission('print:library-category:delete')")
    public CommonResult<Boolean> delete(@PathVariable String id,@RequestParam MultiValueMap<String,String> q){empty(q);service.delete(id);return CommonResult.success(false);}
    private static void empty(MultiValueMap<String,String> q){if(!q.isEmpty())throw new PrintLibraryCategoryException(INVALID_INPUT);}
    private static int number(MultiValueMap<String,String> q,String key,int fallback){if(!q.containsKey(key))return fallback;
        var values=q.get(key);if(values==null||values.size()!=1||!values.get(0).matches("[1-9][0-9]{0,6}"))throw new PrintLibraryCategoryException(INVALID_INPUT);
        return Integer.parseInt(values.get(0));
    }
    @ExceptionHandler(PrintLibraryCategoryException.class)
    public ResponseEntity<CommonResult<Object>> error(PrintLibraryCategoryException e){return ResponseEntity.status(e.reason.status).body(CommonResult.error(e.reason.code,e.getMessage()));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<CommonResult<Object>> storage(RuntimeException ignored){return error(new PrintLibraryCategoryException(STORAGE_UNAVAILABLE));}
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Object>> input(HttpMessageNotReadableException ignored){return error(new PrintLibraryCategoryException(INVALID_INPUT));}
}
