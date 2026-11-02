package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import cn.iocoder.yudao.server.print.miniappdemo.imagecollage.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoCollageUploadException.Reason.INVALID_INPUT;

@RestController
@RequestMapping("/print/miniapp/demo/image-collage-upload-sessions")
@TenantIgnore
public class PrintMiniappDemoCollageUploadController {
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoCollageUploadService uploads;
    public PrintMiniappDemoCollageUploadController(MiniappDemoSessionService sessions,MiniappDemoCollageUploadService uploads){
        this.sessions=sessions;this.uploads=uploads;
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-collage-upload",operateName="create")
    public ResponseEntity<CommonResult<MiniappDemoCollageUploadService.View>> create(
            @RequestBody(required=false) JsonNode body,HttpServletRequest request){
        noQuery(request);
        return ok(uploads.create(identity(request),body,request.getHeader("Idempotency-Key")));
    }
    @PostMapping(value="/{uploadId}/parts/{index}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-collage-upload",operateName="part")
    public ResponseEntity<CommonResult<MiniappDemoCollageUploadService.View>> part(
            @PathVariable String uploadId,@PathVariable int index,
            @RequestPart(value="file",required=false) MultipartFile file,HttpServletRequest request)throws Exception {
        noQuery(request);
        var identity=identity(request);
        Collection<Part> parts=request.getParts();
        if(parts.size()!=1||file==null||file.isEmpty())throw invalid();
        Part part=parts.iterator().next();
        if(!"file".equals(part.getName())||part.getSubmittedFileName()==null||part.getSize()!=file.getSize())throw invalid();
        return ok(uploads.upload(identity,uploadId,index,file));
    }
    @PostMapping("/{uploadId}/complete") @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-collage-upload",operateName="complete")
    public ResponseEntity<CommonResult<MiniappDemoCollageUploadService.View>> complete(
            @PathVariable String uploadId,@RequestBody(required=false) byte[] body,HttpServletRequest request){
        noQuery(request);if(body!=null&&body.length!=0)throw invalid();
        return ok(uploads.complete(identity(request),uploadId));
    }
    @DeleteMapping("/{uploadId}") @PermitAll
    @ApiAccessLog(requestEnable=false,responseEnable=false,operateModule="print-miniapp-demo-collage-upload",operateName="cancel")
    public ResponseEntity<CommonResult<Boolean>> cancel(
            @PathVariable String uploadId,@RequestBody(required=false) byte[] body,HttpServletRequest request){
        noQuery(request);if(body!=null&&body.length!=0)throw invalid();
        uploads.cancel(identity(request),uploadId);return ok(Boolean.TRUE);
    }
    private MiniappDemoIdentity identity(HttpServletRequest r){
        return sessions.requireForWrite(r.getHeader("Authorization"),r.getHeader("Origin"),r.getHeader("Referer"),r.getHeader("Host"));
    }
    private static void noQuery(HttpServletRequest r){if(r.getQueryString()!=null)throw invalid();}
    private static MiniappDemoCollageUploadException invalid(){return new MiniappDemoCollageUploadException(INVALID_INPUT);}
    @ExceptionHandler(MiniappDemoCollageUploadException.class)
    public ResponseEntity<CommonResult<Object>> stagedError(MiniappDemoCollageUploadException e){return error(e.status().value(),e.code(),e.getMessage());}
    @ExceptionHandler(MiniappDemoSessionException.class)
    public ResponseEntity<CommonResult<Object>> sessionError(MiniappDemoSessionException e){return error(e.status().value(),e.code(),e.getMessage());}
    @ExceptionHandler(MiniappDemoImageCollageException.class)
    public ResponseEntity<CommonResult<Object>> collageError(MiniappDemoImageCollageException e){return error(e.status().value(),e.code(),e.getMessage());}
    @ExceptionHandler(MiniappDemoFileException.class)
    public ResponseEntity<CommonResult<Object>> fileError(MiniappDemoFileException e){return error(e.status().value(),e.code(),e.getMessage());}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.multipart.MultipartException.class,jakarta.servlet.ServletException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<CommonResult<Object>> inputError(Exception e){return stagedError(invalid());}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Object>> safeError(Exception e){
        return stagedError(new MiniappDemoCollageUploadException(MiniappDemoCollageUploadException.Reason.STORAGE_UNAVAILABLE));
    }
    private static <T> ResponseEntity<CommonResult<T>> ok(T data){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache").body(CommonResult.success(data));
    }
    private static ResponseEntity<CommonResult<Object>> error(int status,int code,String message){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA,"no-cache")
            .body(CommonResult.error(code,message));
    }
}
