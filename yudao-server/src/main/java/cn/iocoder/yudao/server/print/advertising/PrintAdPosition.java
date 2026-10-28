package cn.iocoder.yudao.server.print.advertising;

/** API keys deliberately differ from observed legacy adtype query parameters. */
public enum PrintAdPosition {
    COMPUTER_TERMINAL(5,274,540), TOUCH_TERMINAL(5,720,240), FULLSCREEN_TERMINAL(5,755,585),
    PORTRAIT_TERMINAL(5,1080,1241), CLOUD_BOX(5,1920,1080), ORDER_PAGE(2,null,null),
    COMPUTER_DESKTOP(1,1920,1080), MINI_SHARE(1,627,486);
    public final int maximum; public final Integer observedWidth,observedHeight;
    PrintAdPosition(int maximum,Integer width,Integer height){this.maximum=maximum;this.observedWidth=width;this.observedHeight=height;}
    public static PrintAdPosition parse(String value){
        try{return valueOf(value);}catch(RuntimeException e){throw new PrintAdvertisingException(PrintAdvertisingException.Reason.INVALID);}
    }
}
