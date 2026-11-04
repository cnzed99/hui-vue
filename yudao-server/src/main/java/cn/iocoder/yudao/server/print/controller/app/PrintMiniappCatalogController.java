package cn.iocoder.yudao.server.print.controller.app;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.server.print.controller.app.vo.PrintMiniappCatalogRespVO;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public product capability metadata only. No database, tenant scope, user data or write path. */
@RestController
@RequestMapping("/print/miniapp/catalog")
public class PrintMiniappCatalogController {

    private static final PrintMiniappCatalogRespVO CATALOG = new PrintMiniappCatalogRespVO(
            "miniapp-pdf-sources-2026-11-04",
            List.of(
                    method("wechat-chat", "微信聊天文档打印", "当前支持 PDF；Word、Excel、PPT 待接入", "COMMON", "PDF_AVAILABLE"),
                    method("local-document", "本地文档打印", "选择本地 PDF 文件打印", "OTHER", "PDF_AVAILABLE"),
                    method("archive", "压缩包打印", "上传 ZIP 并选择其中的 PDF 打印", "OTHER", "PDF_AVAILABLE"),
                    method("tencent-doc", "腾讯文档打印", "导入腾讯文档后打印", "OTHER", "RESERVED"),
                    method("kingsoft-doc", "金山文档打印", "导入金山文档后打印", "OTHER", "RESERVED"),
                    method("baidu-drive", "百度网盘文档打印", "从百度网盘选择文档", "OTHER", "RESERVED"),
                    method("other-app", "其它 APP 文档打印", "钉钉、WPS、邮箱等应用内文件打印", "OTHER", "RESERVED"),
                    method("a3-to-a4", "A3 文档转 A4", "转换版面后打印", "OTHER", "RESERVED"),
                    method("pdf-unlock", "PDF 解密打印", "解密能力待正式接入", "OTHER", "RESERVED"),
                    method("buy-paper", "购买 A4 白纸", "商品与库存接口待接入", "OTHER", "RESERVED")
            ),
            List.of("pdf"),
            new PrintMiniappCatalogRespVO.DemoLimits(2L * 1024 * 1024, 20, 60),
            true
    );

    @GetMapping
    @PermitAll
    @TenantIgnore
    @ApiAccessLog(requestEnable = false, responseEnable = false, operateModule = "print-miniapp-catalog")
    public CommonResult<PrintMiniappCatalogRespVO> getCatalog() {
        return CommonResult.success(CATALOG);
    }

    private static PrintMiniappCatalogRespVO.PrintMethod method(
            String id, String label, String description, String group, String availability) {
        return new PrintMiniappCatalogRespVO.PrintMethod(id, label, description, group, availability);
    }
}
