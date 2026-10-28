package cn.iocoder.yudao.server.print.printer;
import java.util.List;
/** Metadata only. Neither printer status nor defaults are inferred from terminal state. */
public final class PrintPrinterDirectoryModel {
    private PrintPrinterDirectoryModel(){}
    public record Printer(String id,String name,String terminalId,String terminalName,String address,Boolean defaultPrinter,Boolean mobileSelfService,String updatedAt,String reportStatus){}
    public record TerminalOption(String id,String name,String hostName,Boolean mobileSelfService){}
    public record Page(List<Printer> list,long total,int page,int pageSize,List<TerminalOption> terminals,String statusBasis){}
}
