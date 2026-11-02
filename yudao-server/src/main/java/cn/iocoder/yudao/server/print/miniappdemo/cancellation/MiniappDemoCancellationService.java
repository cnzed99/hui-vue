package cn.iocoder.yudao.server.print.miniappdemo.cancellation;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionRepository;
import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderService;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.iocoder.yudao.server.print.miniappdemo.cancellation.MiniappDemoCancellationException.Reason.*;

@Service
public class MiniappDemoCancellationService {
    private final MiniappDemoCancellationProperties properties;
    private final MiniappDemoCancellationRepository repository;
    private final MiniappDemoSessionRepository sessions;
    private final MiniappDemoOrderService orders;
    private final PrintScopeService scopes;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    public MiniappDemoCancellationService(MiniappDemoCancellationProperties properties,
            MiniappDemoCancellationRepository repository, MiniappDemoSessionRepository sessions,
            MiniappDemoOrderService orders, PrintScopeService scopes, PlatformTransactionManager manager) {
        this.properties=properties; this.repository=repository; this.sessions=sessions; this.orders=orders; this.scopes=scopes;
        write=new TransactionTemplate(manager); write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); read.setReadOnly(true);
    }
    public MiniappDemoCancellationView get(MiniappDemoIdentity owner,String orderId) {
        requireInput(owner,orderId);
        return read.execute(status -> getWithin(owner,orderId));
    }
    public MiniappDemoCancellationView getAdmin(String orderId) {
        if (!uuid(orderId)) throw failure(INVALID_INPUT);
        var scope=scopes.requireCurrent();
        return read.execute(status -> {
            var order=repository.orderAdmin(scope,orderId).orElseThrow(()->failure(ORDER_NOT_FOUND));
            return getWithin(new MiniappDemoIdentity("",order.principalId(),scope.tenantId(),scope.storeId(),Long.MAX_VALUE),orderId);
        });
    }
    private MiniappDemoCancellationView getWithin(MiniappDemoIdentity owner,String id) {
        var order=repository.order(owner,id,false).orElseThrow(()->failure(ORDER_NOT_FOUND));
        requireOrder(order);
        var task=repository.task(owner,id,false).orElse(null);
        var entry=repository.byOrder(owner,id,false).orElse(null);
        var current=orders.get(owner,id);
        var capability=entry!=null ? new MiniappDemoCancellationView.Capability("NONE","ALREADY_RECORDED",order.version())
                : !properties.isEnabled() ? new MiniappDemoCancellationView.Capability("NONE","WRITE_DISABLED",order.version())
                : capability(order,task);
        return new MiniappDemoCancellationView(capability,current,entry==null?null:actionView(owner,order,entry));
    }
    public MiniappDemoCancellationView.ActionView create(MiniappDemoIdentity owner,JsonNode body,String key) {
        properties.requireWritesEnabled();
        if (owner==null || body==null || !body.isObject() || body.size()!=2 || !body.has("orderId") || !body.get("orderId").isTextual()
                || !uuid(body.get("orderId").textValue()) || !body.has("expectedVersion") || !body.get("expectedVersion").isIntegralNumber()
                || !body.get("expectedVersion").canConvertToLong() || body.get("expectedVersion").longValue()<1
                || key==null || !key.matches("[!-~]{1,128}")) throw failure(INVALID_INPUT);
        String id=body.get("orderId").textValue(); long expected=body.get("expectedVersion").longValue();
        String keyHash=hash(key), inputHash=hash("cancellation-v1\n"+id+"\n"+expected);
        return write.execute(status -> {
            var locked=sessions.lockIdentity(owner).orElseThrow(MiniappDemoCancellationService::sessionInvalid);
            if (!locked.activeAt(System.currentTimeMillis())) throw sessionInvalid();
            var order=repository.order(owner,id,true).orElseThrow(()->failure(ORDER_NOT_FOUND));
            requireOrder(order);
            var sameKey=repository.byKey(owner,keyHash);
            if (sameKey.isPresent()) {
                if (!sameKey.get().orderId().equals(id) || !sameKey.get().inputHash().equals(inputHash)) throw failure(IDEMPOTENCY_CONFLICT);
                if (!locked.activeAt(System.currentTimeMillis())) throw sessionInvalid();
                return actionView(owner,order,sameKey.get());
            }
            if (repository.byOrder(owner,id,true).isPresent()) throw failure(CANCELLATION_ALREADY_RECORDED);
            if (expected!=order.version()) throw failure(STALE_VERSION);
            var task=repository.task(owner,id,true).orElse(null);
            var capability=capability(order,task);
            String outcome=switch(capability.action()) {
                case "CANCEL_UNPAID" -> "CANCELLED_UNPAID";
                case "CANCEL_AND_REFUND" -> "CANCELLED_REFUNDED";
                case "REQUEST_REVIEW" -> "MANUAL_REVIEW_REQUIRED";
                default -> throw failure(STORAGE_UNAVAILABLE);
            };
            MiniappDemoCancellationRepository.Payment payment=null;
            if ("CANCELLED_REFUNDED".equals(outcome)) payment=verifiedPayment(owner,order,true);
            else if ("CANCELLED_UNPAID".equals(outcome) && repository.payment(owner,id,true).isPresent()) throw failure(STORAGE_UNAVAILABLE);
            long now=System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();
            boolean manual="MANUAL_REVIEW_REQUIRED".equals(outcome);
            var entry=new MiniappDemoCancellationRepository.Entry(UUID.randomUUID().toString(),id,keyHash,inputHash,outcome,
                    capability.reason(),order.version(),manual?order.version():Math.addExact(order.version(),1),
                    task==null?null:task.id(),task==null?null:task.state(),task==null?null:manual?task.state():"CANCELLED",
                    task==null?null:task.version(),task==null?null:manual?task.version():2L,
                    task==null?null:task.eventSeq(),task==null?null:manual?task.eventSeq():2L,
                    task==null?null:task.fence(),task==null?null:task.fence(),now,true);
            repository.insertEntry(owner,entry);
            if (!manual) {
                if (task!=null && !repository.cancelTask(owner,id,task,now)) throw failure(STORAGE_UNAVAILABLE);
                if (payment!=null) repository.insertRefund(owner,entry,payment,order);
                if (!repository.cancelOrder(owner,order,entry)) throw failure(STORAGE_UNAVAILABLE);
                repository.event(owner,entry);
            }
            var current=repository.order(owner,id,false).orElseThrow(()->failure(STORAGE_UNAVAILABLE));
            return actionView(owner,current,entry);
        });
    }
    private MiniappDemoCancellationView.Capability capability(MiniappDemoCancellationRepository.Order order,
            MiniappDemoCancellationRepository.Task task) {
        requireOrder(order);
        if (!"OPEN".equals(order.orderState()) || !"NONE".equals(order.refundState())) throw failure(STORAGE_UNAVAILABLE);
        if (task==null) {
            if (!"NOT_CREATED".equals(order.printState())) throw failure(STORAGE_UNAVAILABLE);
            if ("UNPAID".equals(order.paymentState()) && order.version()==1) return cap("CANCEL_UNPAID","UNPAID",order);
            if ("SUCCEEDED".equals(order.paymentState()) && order.version()==2) return cap("CANCEL_AND_REFUND","NEVER_CLAIMED",order);
            throw failure(STORAGE_UNAVAILABLE);
        }
        if (!"SUCCEEDED".equals(order.paymentState()) || !task.state().equals(order.printState())
                || order.version()!=task.version()+2 || task.eventSeq()!=task.version()) throw failure(STORAGE_UNAVAILABLE);
        if ("QUEUED".equals(task.state()) && task.version()==1 && task.eventSeq()==1 && task.fence()==0
                && task.leaseId()==null && task.leaseUntil()==null && order.version()==3) return cap("CANCEL_AND_REFUND","NEVER_CLAIMED",order);
        if (!Set.of("QUEUED","CLAIMED","ACCEPTED","EXECUTING","SUCCEEDED","FAILED","RESULT_UNKNOWN").contains(task.state())
                || "QUEUED".equals(task.state()) && task.fence()<1) throw failure(STORAGE_UNAVAILABLE);
        return cap("REQUEST_REVIEW","REQUIRES_MANUAL_REVIEW",order);
    }
    private MiniappDemoCancellationView.ActionView actionView(MiniappDemoIdentity owner,
            MiniappDemoCancellationRepository.Order order,MiniappDemoCancellationRepository.Entry e) {
        if (!e.orderId().equals(order.id()) || !e.simulation()) throw failure(STORAGE_UNAVAILABLE);
        var refund=repository.refund(owner,order.id()).orElse(null);
        boolean refunded="CANCELLED_REFUNDED".equals(e.outcome()), manual="MANUAL_REVIEW_REQUIRED".equals(e.outcome());
        if (refunded!=(refund!=null)) throw failure(STORAGE_UNAVAILABLE);
        var task=repository.task(owner,order.id(),false).orElse(null);
        if (!manual) {
            if (!"CANCELLED".equals(order.orderState()) || order.version()!=e.orderVersionAfter()
                    || !(refunded?"SUCCEEDED":"NONE").equals(order.refundState())
                    || !(refunded?"SUCCEEDED":"UNPAID").equals(order.paymentState())
                    || e.taskId()==null && (!"NOT_CREATED".equals(order.printState()) || task!=null)
                    || e.taskId()!=null && (task==null || !e.taskId().equals(task.id()) || !"CANCELLED".equals(task.state())
                    || task.version()!=2 || task.eventSeq()!=2 || task.fence()!=0 || task.leaseId()!=null || task.leaseUntil()!=null)) throw failure(STORAGE_UNAVAILABLE);
        } else {
            // The request snapshot is historical; the original task may continue advancing afterwards.
            if (task==null || !e.taskId().equals(task.id()) || !"OPEN".equals(order.orderState())
                    || !"SUCCEEDED".equals(order.paymentState()) || !"NONE".equals(order.refundState())
                    || !task.state().equals(order.printState()) || order.version()!=task.version()+2) throw failure(STORAGE_UNAVAILABLE);
        }
        MiniappDemoCancellationView.RefundReceipt receipt=null;
        if (refunded) {
            var payment=verifiedPayment(owner,order,false);
            if (!refund.cancellationId().equals(e.id()) || !refund.paymentReceiptId().equals(payment.id())
                    || !refund.orderId().equals(order.id()) || !"SIMULATED".equals(refund.provider()) || !"DEMO".equals(refund.mode())
                    || !"SUCCEEDED".equals(refund.state()) || !refund.simulation() || !order.amount().equals(refund.amount())
                    || !order.currency().equals(refund.currency())) throw failure(STORAGE_UNAVAILABLE);
            var journals=repository.refundJournals(owner,refund.id());
            var debit=journal(journals,"DEBIT","DEMO_STORE",order);
            var credit=journal(journals,"CREDIT","DEMO_CUSTOMER",order);
            receipt=new MiniappDemoCancellationView.RefundReceipt(refund.id(),e.id(),payment.id(),order.id(),refund.provider(),refund.mode(),refund.state(),true,
                    refund.currency(),refund.amount(),refund.createdAtMs(),view(debit),view(credit));
        }
        var original=new MiniappDemoCancellationView.OriginalResult(e.id(),e.orderId(),e.outcome(),e.reason(),e.orderVersionBefore(),e.orderVersionAfter(),
                e.taskId(),e.taskStateBefore(),e.taskStateAfter(),e.taskVersionBefore(),e.taskVersionAfter(),e.taskFenceBefore(),e.taskFenceAfter(),e.createdAtMs(),true);
        var review=manual?new MiniappDemoCancellationView.ReviewSummary(e.id(),"PENDING",e.taskId(),e.taskStateBefore(),e.taskVersionBefore(),e.taskFenceBefore(),e.createdAtMs()):null;
        return new MiniappDemoCancellationView.ActionView(original,orders.get(owner,order.id()),receipt,review);
    }
    private MiniappDemoCancellationRepository.Payment verifiedPayment(MiniappDemoIdentity owner,MiniappDemoCancellationRepository.Order order,boolean lock) {
        var payment=repository.payment(owner,order.id(),lock).orElseThrow(()->failure(STORAGE_UNAVAILABLE));
        if (!"SIMULATED".equals(payment.provider()) || !"DEMO".equals(payment.mode()) || !"SUCCEEDED".equals(payment.state()) || !payment.simulation()
                || !order.id().equals(payment.orderId()) || !order.amount().equals(payment.amount()) || !order.currency().equals(payment.currency())
                || !order.amount().matches("(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw failure(STORAGE_UNAVAILABLE);
        var rows=repository.paymentJournals(owner,payment.id(),lock);
        journal(rows,"DEBIT","DEMO_CUSTOMER",order); journal(rows,"CREDIT","DEMO_STORE",order);
        return payment;
    }
    private static MiniappDemoCancellationRepository.Journal journal(List<MiniappDemoCancellationRepository.Journal> rows,String side,String account,MiniappDemoCancellationRepository.Order order) {
        var matching=rows.stream().filter(r->side.equals(r.side())).toList();
        if (rows.size()!=2 || matching.size()!=1) throw failure(STORAGE_UNAVAILABLE);
        var row=matching.get(0);
        if (!account.equals(row.account()) || !order.currency().equals(row.currency()) || !order.amount().equals(row.amount())) throw failure(STORAGE_UNAVAILABLE);
        return row;
    }
    private static MiniappDemoCancellationView.Journal view(MiniappDemoCancellationRepository.Journal r) { return new MiniappDemoCancellationView.Journal(r.side(),r.account(),r.currency(),r.amount()); }
    private static MiniappDemoCancellationView.Capability cap(String action,String reason,MiniappDemoCancellationRepository.Order order) { return new MiniappDemoCancellationView.Capability(action,reason,order.version()); }
    private static void requireInput(MiniappDemoIdentity owner,String id) { if(owner==null || !uuid(id)) throw failure(INVALID_INPUT); }
    private static void requireOrder(MiniappDemoCancellationRepository.Order o) { if(!"MINIAPP_DEMO".equals(o.source()) || !o.simulation() || !"CNY".equals(o.currency())) throw failure(STORAGE_UNAVAILABLE); }
    private static boolean uuid(String v) { return v!=null && v.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static String hash(String v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); } }
    private static MiniappDemoCancellationException failure(MiniappDemoCancellationException.Reason reason) { return new MiniappDemoCancellationException(reason); }
    private static MiniappDemoSessionException sessionInvalid() { return new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID); }
}
