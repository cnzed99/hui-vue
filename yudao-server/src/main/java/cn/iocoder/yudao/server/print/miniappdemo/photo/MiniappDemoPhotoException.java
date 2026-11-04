package cn.iocoder.yudao.server.print.miniappdemo.photo;
public class MiniappDemoPhotoException extends RuntimeException {
    private final int status; private final int code;
    public MiniappDemoPhotoException(int status,int code,String message){super(message);this.status=status;this.code=code;}
    public int status(){return status;} public int code(){return code;}
    public static MiniappDemoPhotoException input(){return new MiniappDemoPhotoException(400,43200,"Invalid photo input");}
    public static MiniappDemoPhotoException unavailable(){return new MiniappDemoPhotoException(503,43205,"Photo result unknown; recover the original upload session");}
}
