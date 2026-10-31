package cn.iocoder.yudao.server.print.miniappdemo.quote;

/** VALID only means the quote has not expired; it does not mean ordered, paid, or printable. */
public record MiniappDemoQuoteView(String id, String status, MiniappDemoQuoteSnapshot snapshot) {}
