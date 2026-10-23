package cn.iocoder.yudao.server.print.quote;
/** VALID only describes time validity, not authorization to place or fulfill an order. */
public record PrintQuoteView(String id, String status, PrintQuoteSnapshot snapshot) { }
