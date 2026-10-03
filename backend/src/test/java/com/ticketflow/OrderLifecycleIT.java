package com.ticketflow;

import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.mapper.PaymentMapper;
import com.ticketflow.model.dto.CreateOrderDTO;
import com.ticketflow.service.*;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLifecycleIT extends OrderTestSupport {
    @MockitoSpyBean PaymentMapper payments;
    @MockitoSpyBean PaymentSimulator simulator;
    @MockitoSpyBean OrderApplicationService service;
    @Autowired DatabaseClock clock;
    @AfterEach void clearLifecycleFaults() { reset(payments,simulator,service); }
    long buy(Actor user, Fixture f) throws Exception { return Long.parseLong(data(create(user,f.tier(),key()),201).path("orderId").asString()); }
    HttpResponse<String> act(Actor user, long order, String operation, String key) throws Exception { return request("POST","/api/v1/orders/"+order+"/"+operation,Map.of(),user.token(),key); }
    String status(long order) { return db.queryForObject("SELECT status FROM tf_order WHERE id=?",String.class,order); }
    void expire(long order) { db.update("UPDATE tf_order SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE id=?",order); }
    void ledger(Fixture f) {
        assertEquals(0,count("SELECT COUNT(*) FROM tf_stock s JOIN tf_tier t ON t.id=s.tier_id WHERE t.session_id=? AND (s.capacity<>s.available+s.reserved+s.sold OR s.reserved<>(SELECT COUNT(*) FROM tf_order o WHERE o.tier_id=t.id AND o.status='PENDING') OR s.sold<>(SELECT COUNT(*) FROM tf_order o WHERE o.tier_id=t.id AND o.status='PAID'))",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_order o LEFT JOIN tf_purchase_slot p ON p.order_id=o.id WHERE o.session_id=? AND ((o.status IN ('PENDING','PAID') AND (p.order_id IS NULL OR p.user_id<>o.user_id OR p.session_id<>o.session_id)) OR (o.status NOT IN ('PENDING','PAID') AND p.order_id IS NOT NULL))",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_order o LEFT JOIN tf_payment p ON p.order_id=o.id LEFT JOIN tf_refund r ON r.order_id=o.id WHERE o.session_id=? AND ((o.status IN ('PAID','REFUNDED') AND (p.id IS NULL OR p.amount_fen<>o.amount_fen)) OR (o.status NOT IN ('PAID','REFUNDED') AND p.id IS NOT NULL) OR (o.status='REFUNDED' AND (r.id IS NULL OR r.amount_fen<>o.amount_fen)) OR (o.status<>'REFUNDED' AND r.id IS NOT NULL))",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_order o WHERE o.session_id=? AND ((SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='RESERVE')<>1 OR (o.status IN ('CANCELLED','CLOSED') AND (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='RELEASE')<>1) OR (o.status IN ('PAID','REFUNDED') AND (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='PAY')<>1) OR (o.status='REFUNDED' AND (SELECT COUNT(*) FROM tf_stock_log l WHERE l.order_id=o.id AND l.movement='REFUND')<>1))",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_request WHERE order_id IN (SELECT id FROM tf_order WHERE session_id=?) AND state='PROCESSING'",f.session()));
    }
    List<HttpResponse<String>> repeat(Actor user,long order,String operation,String key) throws Exception {
        var tasks=new ArrayList<Callable<HttpResponse<String>>>(); for (int i=0;i<20;i++) tasks.add(()->act(user,order,operation,key));
        return simultaneous(tasks);
    }
    @Test void cancellationRepeatsAndReleasesForAnotherPurchaseIncludingExpiredCancellation() throws Exception {
        var user=actor(false); var f=fixture(1); String createKey=key(); long order=Long.parseLong(data(create(user,f.tier(),createKey),201).path("orderId").asString());
        String cancelKey=key(); for (var r:repeat(user,order,"cancel",cancelKey)) assertEquals("CANCELLED",data(r,200).path("operationStatus").asString());
        data(act(user,order,"cancel",key()),200); assertEquals(1,count("SELECT COUNT(*) FROM tf_stock_log WHERE order_id=? AND movement='RELEASE'",order)); ledger(f);
        var replay=create(user,f.tier(),createKey); assertEquals("PENDING",data(replay,201).path("operationStatus").asString()); assertEquals("CANCELLED",body(replay).path("data").path("currentOrderStatus").asString());
        long next=buy(user,f); expire(next); assertEquals("CLOSED",data(act(user,next,"cancel",key()),200).path("operationStatus").asString());
        data(act(user,next,"cancel",key()),200); assertFalse(service.closeExpired(next)); ledger(f);
        buy(user,f); ledger(f);
    }
    @Test void repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility() throws Exception {
        var user=actor(false); var f=fixture(1); String createKey=key(); long order=Long.parseLong(data(create(user,f.tier(),createKey),201).path("orderId").asString());
        String payKey=key(), refundKey=key(), paymentId=null, refundId=null;
        for (var r:repeat(user,order,"payments",payKey)) {
            String id=data(r,200).path("paymentId").asString(); if (paymentId==null) paymentId=id; else assertEquals(paymentId,id);
        }
        assertEquals(paymentId,data(act(user,order,"payments",key()),200).path("paymentId").asString()); ledger(f);
        var detail=data(request("GET","/api/v1/orders/"+order,null,user.token(),null),200); assertEquals(58000,detail.path("payment").path("amountFen").asLong()); assertTrue(detail.path("refund").isNull());
        // A batch 3 request row without new fields remains readable after the upgrade.
        db.update("UPDATE tf_request SET result_json=JSON_REMOVE(result_json,'$.data.paymentId','$.data.refundId') WHERE user_id=? AND operation='CREATE' AND request_key=?",user.id(),createKey);
        assertEquals("PAID",data(create(user,f.tier(),createKey),201).path("currentOrderStatus").asString());
        for (var r:repeat(user,order,"refunds",refundKey)) { String id=data(r,200).path("refundId").asString(); if (refundId==null) refundId=id; else assertEquals(refundId,id); }
        assertEquals(refundId,data(act(user,order,"refunds",key()),200).path("refundId").asString());
        for (String key:List.of(payKey,key())) {
            var result=data(act(user,order,"payments",key),200); assertEquals(paymentId,result.path("paymentId").asString());
            assertEquals("PAID",result.path("operationStatus").asString()); assertEquals("REFUNDED",result.path("currentOrderStatus").asString());
        }
        assertEquals(refundId,data(act(user,order,"refunds",refundKey),200).path("refundId").asString());
        detail=data(request("GET","/api/v1/orders/"+order,null,user.token(),null),200); assertEquals(paymentId,detail.path("payment").path("paymentId").asString()); assertEquals(refundId,detail.path("refund").path("refundId").asString());
        assertEquals(58000,detail.path("refund").path("amountFen").asLong()); ledger(f); buy(user,f); ledger(f);
    }
    @Test void simulatedFailureIsImmutableForOriginalKeyAndNewKeyCanRetry() throws Exception {
        var user=actor(false); var f=fixture(1); long order=buy(user,f); String payKey=key(), refundKey=key();
        doReturn(false).when(simulator).pay(order,58000);
        var rejectedPayment=act(user,order,"payments",payKey); assertEquals(422,rejectedPayment.statusCode()); assertEquals("PAYMENT_SIMULATED_FAILURE",body(rejectedPayment).path("code").asString()); assertEquals("PENDING",status(order)); ledger(f);
        reset(simulator); var failure=act(user,order,"payments",payKey); assertEquals(422,failure.statusCode()); assertTrue(body(failure).path("replayed").asBoolean());
        data(act(user,order,"payments",key()),200); doReturn(false).when(simulator).refund(order,58000);
        var rejectedRefund=act(user,order,"refunds",refundKey); assertEquals(422,rejectedRefund.statusCode()); assertEquals("REFUND_SIMULATED_FAILURE",body(rejectedRefund).path("code").asString()); assertEquals("PAID",status(order)); ledger(f);
        reset(simulator); failure=act(user,order,"refunds",refundKey); assertEquals(422,failure.statusCode()); assertTrue(body(failure).path("replayed").asBoolean());
        data(act(user,order,"refunds",key()),200); ledger(f);
    }
    @Test void unauthorizedOperationsAndNonemptyBodiesHaveNoSideEffects() throws Exception {
        var user=actor(false); var other=actor(false); var f=fixture(1); long order=buy(user,f);
        for (String operation:List.of("cancel","payments","refunds")) {
            assertEquals(401,request("POST","/api/v1/orders/"+order+"/"+operation,Map.of(),null,key()).statusCode());
            var denied=act(other,order,operation,key()); var missing=act(other,Long.MAX_VALUE,operation,key());
            assertEquals(404,denied.statusCode()); assertEquals(404,missing.statusCode()); assertEquals(body(denied).path("code"),body(missing).path("code"));
            for (Object input:List.of(Map.of("amountFen",1),Map.of("success",true),Map.of("status","PAID"),List.of())) {
                String key=key(); assertEquals(400,request("POST","/api/v1/orders/"+order+"/"+operation,input,user.token(),key).statusCode()); noRequest(user,key);
            }
            assertEquals(400,request("POST","/api/v1/orders/"+order+"/"+operation,Map.of(),user.token(),null).statusCode());
        }
        assertEquals("PENDING",status(order)); ledger(f);
    }
    @Test void lifecycleStateMatrixAndIdempotencyScopeAreEnforced() throws Exception {
        var user=actor(false);
        for (String initial:List.of("PENDING","PAID","CANCELLED","CLOSED","REFUNDED")) {
            for (String operation:List.of("payments","cancel","refunds")) {
                var f=fixture(1); long order=buy(user,f);
                if (List.of("PAID","REFUNDED").contains(initial)) data(act(user,order,"payments",key()),200);
                if (initial.equals("REFUNDED")) data(act(user,order,"refunds",key()),200);
                if (initial.equals("CANCELLED")) data(act(user,order,"cancel",key()),200);
                if (initial.equals("CLOSED")) { expire(order); assertTrue(service.closeExpired(order)); }
                boolean allowed=operation.equals("payments")?List.of("PENDING","PAID","REFUNDED").contains(initial):operation.equals("cancel")?List.of("PENDING","CANCELLED","CLOSED").contains(initial):List.of("PAID","REFUNDED").contains(initial);
                var response=act(user,order,operation,key()); assertEquals(allowed?200:409,response.statusCode(),initial+" "+operation+" "+response.body());
                if (!allowed) assertEquals("ORDER_STATE_CONFLICT",body(response).path("code").asString());
                assertFalse(service.closeExpired(order)); ledger(f);
            }
        }
        var f=fixture(1); long one=buy(user,f); String key=key(); data(act(user,one,"cancel",key),200);
        long two=buy(user,f); rejected(act(user,two,"cancel",key),"IDEMPOTENCY_CONFLICT");
        // The same key can identify PAY and REFUND independently.
        data(act(user,two,"payments",key),200); data(act(user,two,"refunds",key),200); ledger(f);
    }
    @Test void paymentAndCancellationCompetitionHonorsBothLockOrders() throws Exception {
        for (String first:List.of("payments","cancel")) race(first,first.equals("payments")?"cancel":"payments",false);
    }
    @Test void paymentAndSystemCloseCompetitionHonorsBothLockOrders() throws Exception {
        race("payments","close",false); race("close","payments",true);
    }
    void race(String first,String second,boolean expired) throws Exception {
        var user=actor(false); var f=fixture(1); long order=buy(user,f); if (expired) expire(order);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var once=new AtomicBoolean();
        doAnswer(call->{var row=call.callRealMethod(); if (once.compareAndSet(false,true)) { locked.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)); } return row;}).when(orders).lockOwned(user.id(),order);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<Object> winner=pool.submit(()->first.equals("close")?service.closeExpired(order):act(user,order,first,key()));
            assertTrue(locked.await(3,TimeUnit.SECONDS));
            Future<Object> loser=pool.submit(()->second.equals("close")?service.closeExpired(order):act(user,order,second,key()));
            awaitSql("SELECT % FROM tf_user WHERE id=%FOR UPDATE"); release.countDown();
            Object a=winner.get(10,TimeUnit.SECONDS), b=loser.get(10,TimeUnit.SECONDS);
            if (a instanceof HttpResponse<?> response) assertEquals(200,response.statusCode()); else assertEquals(true,a);
            if (b instanceof HttpResponse<?> response) assertEquals(409,response.statusCode()); else assertEquals(false,b);
            assertEquals(first.equals("payments")?"PAID":first.equals("cancel")?"CANCELLED":"CLOSED",status(order)); ledger(f);
        } finally { release.countDown(); pool.shutdownNow(); reset(orders); }
    }
    @Test void paymentAndRefundWaitForStockThenRecheckTheirDeadlines() throws Exception {
        for (String operation:List.of("payments","refunds")) {
            var user=actor(false); var f=fixture(1); long order=buy(user,f);
            if (operation.equals("refunds")) data(act(user,order,"payments",key()),200);
            var boundary=trades.now().plusNanos(900_000_000);
            if (operation.equals("payments")) db.update("UPDATE tf_order SET expires_at=? WHERE id=?",boundary.toString().replace('T',' '),order);
            else db.update("UPDATE tf_order SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE,starts_at=? WHERE id=?",boundary.toString().replace('T',' '),order);
            var pool=Executors.newSingleThreadExecutor();
            try (var lock=hold("tf_stock","tier_id",f.tier())) {
                var pending=pool.submit(()->act(user,order,operation,key())); awaitSql("SELECT available FROM tf_stock WHERE tier_id=%FOR UPDATE");
                while (trades.now().isBefore(boundary.plusNanos(100_000_000))) Thread.sleep(10);
                lock.commit(); rejected(pending.get(10,TimeUnit.SECONDS),operation.equals("payments")?"ORDER_EXPIRED":"REFUND_CLOSED");
                assertEquals(operation.equals("payments")?"PENDING":"PAID",status(order)); ledger(f);
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void everyLifecycleWriteFailureRollsBackAndOriginalKeyCanRecover() throws Exception {
        for (String operation:List.of("cancel","payments","refunds")) {
            int stages=operation.equals("cancel")?5:operation.equals("payments")?5:6;
            for (int stage=0;stage<stages;stage++) {
                var user=actor(false); var f=fixture(1); long order=buy(user,f); String key=key();
                if (operation.equals("refunds")) data(act(user,order,"payments",key()),200);
                String before=status(order);
                if (stage==0) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("transition fault");}).when(orders).transition(any(),any(),any());
                if (stage==1) {
                    if (operation.equals("cancel")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("stock fault");}).when(inventory).release(anyLong(),any());
                    if (operation.equals("payments")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("stock fault");}).when(inventory).markSold(anyLong(),any());
                    if (operation.equals("refunds")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("stock fault");}).when(inventory).refund(anyLong(),any());
                }
                if (stage==2) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("log fault");}).when(inventory).log(anyLong(),anyString(),anyInt(),anyInt(),anyInt(),any());
                if (stage==3) {
                    if (operation.equals("cancel")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("slot fault");}).when(orders).deleteSlot(any());
                    if (operation.equals("payments")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("payment fault");}).when(payments).insertPayment(anyLong(),anyLong(),any());
                    if (operation.equals("refunds")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("refund fault");}).when(payments).insertRefund(anyLong(),anyLong(),any());
                }
                if (stage==4 && operation.equals("refunds")) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("slot fault");}).when(orders).deleteSlot(any());
                if (stage==stages-1) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("result fault");}).when(trades).complete(anyLong(),anyString(),anyInt(),anyString(),anyString(),nullable(Long.class));
                assertEquals(500,act(user,order,operation,key).statusCode()); reset(orders,inventory,payments,trades);
                assertEquals(before,status(order)); noRequest(user,key); ledger(f); data(act(user,order,operation,key),200); ledger(f);
            }
        }
    }
    @Test void knownSimulatorRejectionAfterWritesUsesSavepointAndStillCommitsFailure() throws Exception {
        var user=actor(false); var f=fixture(1); long order=buy(user,f); String key=key();
        doAnswer(c->{c.callRealMethod();throw new BusinessRejection(422,"PAYMENT_SIMULATED_FAILURE","受控失败");}).when(payments).insertPayment(anyLong(),anyLong(),any());
        assertEquals(422,act(user,order,"payments",key).statusCode()); reset(payments);
        assertEquals("PENDING",status(order)); ledger(f);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_request WHERE user_id=? AND request_key=? AND state='REJECTED'",user.id(),key));
        data(act(user,order,"payments",key()),200); ledger(f);
    }
    @Test void disabledOwnerAndDuplicateSystemClosesReleaseOnlyOnceAndFailuresRollback() throws Exception {
        var user=actor(false); var f=fixture(1); long order=buy(user,f); expire(order); db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",user.id());
        doAnswer(c->{c.callRealMethod();throw new IllegalStateException("close slot fault");}).when(orders).deleteSlot(any());
        assertThrows(IllegalStateException.class,()->service.closeExpired(order)); reset(orders); assertEquals("PENDING",status(order)); ledger(f);
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return service.closeExpired(order);}); var two=pool.submit(()->{start.await();return service.closeExpired(order);}); start.countDown();
            assertEquals(Set.of(true,false),Set.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS)));
        } finally { pool.shutdownNow(); }
        assertEquals("CLOSED",status(order)); assertEquals(1,count("SELECT COUNT(*) FROM tf_stock_log WHERE order_id=? AND movement='RELEASE'",order)); ledger(f);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_request WHERE user_id=?",user.id()));
    }
    @Test void scanPagesMoreThanHundredOrdersContinuesAfterOneFailureAndRetriesNextRound() throws Exception {
        var user=actor(false); var ids=new ArrayList<Long>(); var fixtures=new ArrayList<Fixture>();
        for (int i=0;i<105;i++) { var f=fixture(1); fixtures.add(f); long id=buy(user,f); expire(id); ids.add(id); }
        long poisoned=ids.get(0); doThrow(new IllegalStateException("single close failure")).when(service).closeExpired(eq(poisoned),anyLong());
        var job=new OrderExpiryJob(orders,service,clock); job.scan(); assertEquals("PENDING",status(poisoned));
        for (long id:ids.subList(1,ids.size())) assertEquals("CLOSED",status(id));
        reset(service); job.scan(); job.scan(); assertEquals("CLOSED",status(poisoned));
        for (var f:fixtures) ledger(f);
        verify(orders,atLeastOnce()).expired(any(),any(),anyLong());
    }
    @Test void applicationRestartRunsStartupRecoveryAndSchedulingClosesLaterExpiries() throws Exception {
        var user=actor(false); var f=fixture(1); long order;
        var properties=new HashMap<String,Object>(); properties.put("server.port","0");
        properties.put("ticketflow.jwt.private-key",IdentityIntegrationIT.testKeys.resolve("private.pem").toString());
        properties.put("ticketflow.jwt.public-key",IdentityIntegrationIT.testKeys.resolve("public.pem").toString());
        properties.put("ticketflow.admin.username",""); properties.put("ticketflow.admin.password",""); properties.put("ticketflow.expiry.enabled","false");
        try (var first=startApplication(properties)) {
            order=Long.parseLong(first.getBean(OrderApplicationService.class).create(user.id(),key(),new CreateOrderDTO(Long.toString(f.tier()),1)).data().orderId());
        }
        expire(order); db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",user.id()); properties.put("ticketflow.expiry.enabled","true");
        try (var restarted=startApplication(properties)) {
            assertEquals("CLOSED",status(order)); ledger(f);
            db.update("UPDATE tf_user SET enabled=TRUE WHERE id=?",user.id());
            long later=Long.parseLong(restarted.getBean(OrderApplicationService.class).create(user.id(),key(),new CreateOrderDTO(Long.toString(f.tier()),1)).data().orderId()); expire(later);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while (!status(later).equals("CLOSED") && System.nanoTime()<deadline) Thread.sleep(50);
            assertEquals("CLOSED",status(later)); ledger(f);
        }
    }
    private org.springframework.context.ConfigurableApplicationContext startApplication(Map<String,Object> properties) {
        String[] args=properties.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(TicketFlowApplication.class).web(WebApplicationType.SERVLET).run(args);
    }
    @Test void slowRoundStopsAtBudgetAndDoesNotOverlapOrLoseNextRoundCandidates() throws Exception {
        var user=actor(false); var f=fixture(2); long one=buy(user,f); expire(one);
        var other=actor(false); long two=buy(other,f); expire(two);
        var entered=new CountDownLatch(1); var attempts=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{ attempts.incrementAndGet(); entered.countDown(); Thread.sleep(9_200); return false; }).when(service).closeExpired(anyLong(),anyLong());
        var job=new OrderExpiryJob(orders,service,clock); var pool=Executors.newSingleThreadExecutor();
        try {
            long began=System.nanoTime(); var round=pool.submit(job::scan); assertTrue(entered.await(3,TimeUnit.SECONDS));
            job.scan(); // Reentrant/concurrent tick cannot start a second round.
            round.get(12,TimeUnit.SECONDS); assertTrue(Duration.ofNanos(System.nanoTime()-began).toMillis()<10_500);
            assertEquals(1,attempts.get()); assertEquals("PENDING",status(one)); assertEquals("PENDING",status(two));
            reset(service); job.scan(); assertEquals("CLOSED",status(one)); assertEquals("CLOSED",status(two)); ledger(f);
        } finally { pool.shutdownNow(); }
    }
    @Test void inconsistentSuccessfulRecordsAreSystemErrorsAndDoNotPersistRejection() throws Exception {
        var user=actor(false); var f=fixture(1); long order=buy(user,f); data(act(user,order,"payments",key()),200);
        var payment=payments.payment(order); db.update("DELETE FROM tf_payment WHERE order_id=?",order);
        try {
            String key=key(); assertEquals(500,act(user,order,"payments",key).statusCode()); noRequest(user,key); assertEquals("PAID",status(order));
        } finally { db.update("INSERT INTO tf_payment(id,order_id,amount_fen,paid_at) VALUES(?,?,?,?)",payment.id(),order,payment.amountFen(),payment.paidAt().toString().replace('T',' ')); }
        db.update("UPDATE tf_payment SET amount_fen=1 WHERE order_id=?",order);
        try { String key=key(); assertEquals(500,act(user,order,"refunds",key).statusCode()); noRequest(user,key); }
        finally { db.update("UPDATE tf_payment SET amount_fen=? WHERE order_id=?",payment.amountFen(),order); }
        data(act(user,order,"refunds",key()),200); var refund=payments.refund(order); db.update("DELETE FROM tf_refund WHERE order_id=?",order);
        try { String key=key(); assertEquals(500,act(user,order,"refunds",key).statusCode()); noRequest(user,key); assertEquals("REFUNDED",status(order)); }
        finally { db.update("INSERT INTO tf_refund(id,order_id,amount_fen,refunded_at) VALUES(?,?,?,?)",refund.id(),order,refund.amountFen(),refund.refundedAt().toString().replace('T',' ')); }
        ledger(f);
    }
}
