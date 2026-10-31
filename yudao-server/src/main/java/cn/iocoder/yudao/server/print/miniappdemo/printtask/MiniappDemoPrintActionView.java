package cn.iocoder.yudao.server.print.miniappdemo.printtask;

public record MiniappDemoPrintActionView(ActionResult originalActionResult,
        MiniappDemoPrintTaskView currentTask) {
    public record ActionResult(String action, String beforeState, String afterState,
            long taskVersionAfter, long orderVersionAfter, long eventSeq,
            Long receiptSeq, long createdAtMs) {}
}
