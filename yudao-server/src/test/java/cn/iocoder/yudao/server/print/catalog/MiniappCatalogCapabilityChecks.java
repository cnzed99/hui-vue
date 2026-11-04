package cn.iocoder.yudao.server.print.catalog;

import cn.iocoder.yudao.server.print.controller.app.PrintMiniappCatalogController;
import java.util.List;

/** Static public contract checks; no database, session, network or write path. */
public final class MiniappCatalogCapabilityChecks {
    public static void main(String[] args) {
        var result = new PrintMiniappCatalogController().getCatalog();
        if (result.getCode() != 0) throw new AssertionError("catalog success");
        var catalog = result.getData();
        if (!catalog.uploadEnabled() || !catalog.supportedExtensions().equals(List.of("pdf")))
            throw new AssertionError("PDF-only upload capability");
        if (catalog.demoLimits().maxFileBytes() != 2L * 1024 * 1024 || catalog.demoLimits().maxPages() != 20
                || catalog.demoLimits().retentionMinutes() != 60) throw new AssertionError("existing limits");
        if (catalog.methods().size() != 10) throw new AssertionError("preserve directory");
        for (var method : catalog.methods()) {
            boolean pdfSource = method.id().equals("wechat-chat") || method.id().equals("local-document")
                    || method.id().equals("archive");
            if (!method.availability().equals(pdfSource ? "PDF_AVAILABLE" : "RESERVED"))
                throw new AssertionError("source capability " + method.id());
        }
        System.out.println("PASS public catalog, two direct PDF sources plus ZIP-contained PDF, unchanged limits");
    }
}
