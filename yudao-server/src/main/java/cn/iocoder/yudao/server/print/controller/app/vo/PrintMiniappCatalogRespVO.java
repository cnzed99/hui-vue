package cn.iocoder.yudao.server.print.controller.app.vo;

import java.util.List;

/** Static, non-sensitive miniapp capability directory. It must not contain tenant or business data. */
public record PrintMiniappCatalogRespVO(
        String catalogVersion,
        List<PrintMethod> methods,
        List<String> supportedExtensions,
        DemoLimits demoLimits,
        boolean uploadEnabled) {

    public record PrintMethod(String id, String label, String description, String group, String availability) {}

    public record DemoLimits(long maxFileBytes, int maxPages, int retentionMinutes) {}
}
