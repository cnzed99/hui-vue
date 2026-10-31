package cn.iocoder.yudao.server.print.miniappdemo.file;

import cn.iocoder.yudao.server.print.file.PdfInspection;

import java.nio.file.Path;

/** Minimal child-process entrypoint. Output is a small machine-only status, never document content. */
public final class MiniappDemoPdfInspectorMain {
    private MiniappDemoPdfInspectorMain() {}

    public static void main(String[] args) {
        if (args.length != 4) System.exit(20);
        try {
            long maxBytes = Long.parseLong(args[2]);
            int maxPages = Integer.parseInt(args[3]);
            int pages = PdfInspection.inspect(Path.of(args[0]), Path.of(args[1]), maxBytes, maxPages);
            System.out.println("OK:" + pages);
        } catch (PdfInspection.Failure failure) {
            System.out.println("FAIL:" + failure.code());
            System.exit(10);
        } catch (RuntimeException failure) {
            System.out.println("FAIL:PARSER_UNAVAILABLE");
            System.exit(20);
        }
    }
}
