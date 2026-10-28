package cn.iocoder.yudao.server.print.printer;
public class PrintPrinterDirectoryException extends RuntimeException {
    public final int status;public final int code;
    public PrintPrinterDirectoryException(int status,int code,String message){super(message);this.status=status;this.code=code;}
    public static PrintPrinterDirectoryException invalid(){return new PrintPrinterDirectoryException(400,1510100001,"打印机查询条件无效");}
}
