package cn.iocoder.yudao.server.print.directory;
public class PrintDirectoryException extends RuntimeException {
    public final int status; public final int code;
    public PrintDirectoryException(int status,int code,String message){super(message);this.status=status;this.code=code;}
    public static PrintDirectoryException invalid(){return new PrintDirectoryException(400,1510090001,"查询条件无效");}
}
