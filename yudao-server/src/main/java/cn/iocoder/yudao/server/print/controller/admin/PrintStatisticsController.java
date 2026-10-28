package cn.iocoder.yudao.server.print.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import cn.iocoder.yudao.server.print.statistics.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/print/statistics")
@PreAuthorize("@ss.hasPermission('print:statistics:query')")
public class PrintStatisticsController {
    private final PrintScopeService scopes;
    private final PrintStatisticsRepository repository;
    public PrintStatisticsController(PrintScopeService scopes,PrintStatisticsRepository repository){this.scopes=scopes;this.repository=repository;}
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-statistics",operateName="query")
    @GetMapping("/terminal")
    public CommonResult<Map<String,Object>> terminal(@RequestParam MultiValueMap<String,String> query){
        var q=PrintStatisticsQuery.parse(query,true);
        var s=scopes.requireCurrent();
        return CommonResult.success(Map.of("list",repository.orders(s,q),"scenes",repository.scenes(s),
                "startDate",q.startDate().toString(),"endDate",q.endDate().toString(),"basis","CURRENT_ACTOR_ORDER_SNAPSHOTS",
                "timezone","Asia/Shanghai","transactionAmountDefinition","PENDING_CONFIRMATION"));
    }
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-statistics",operateName="query")
    @GetMapping("/paper-total")
    public CommonResult<PrintStatisticsRepository.Page> total(@RequestParam MultiValueMap<String,String> query){return paper(query,false);}
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-statistics",operateName="query")
    @GetMapping("/paper-daily")
    public CommonResult<PrintStatisticsRepository.Page> daily(@RequestParam MultiValueMap<String,String> query){return paper(query,true);}
    private CommonResult<PrintStatisticsRepository.Page> paper(MultiValueMap<String,String> query,boolean daily){
        var q=PrintStatisticsQuery.parse(query,false);return CommonResult.success(repository.paper(scopes.requireCurrent(),q,daily));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CommonResult<Object>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(CommonResult.error(1510070001,e.getMessage()));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<CommonResult<Object>> storage(DataAccessException ignored){return ResponseEntity.status(503).body(CommonResult.error(1510070002,"统计数据暂不可用，请联系管理员确认统计数据源已启用。"));}
}
