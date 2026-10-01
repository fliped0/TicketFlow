package com.ticketflow;

import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.mapper.*;
import com.ticketflow.service.*;
import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderIntegrationIT {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) throws Exception {
        IdentityIntegrationIT.configureIsolatedTestResources(properties);
    }
    @AfterAll static void removeKeys() throws Exception { IdentityIntegrationIT.removeTemporaryTestKeys(); }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean CatalogMapper catalog;
    @MockitoSpyBean OrderMapper orders;
    @MockitoSpyBean InventoryMapper inventory;
    @MockitoSpyBean TradeMapper trades;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient http=HttpClient.newHttpClient();
    record Actor(long id, String token) {}
    record Fixture(long event, long session, long tier, long otherTier) {}
    @BeforeEach void isolatedDatabase() { assertEquals("ticketflow_test",db.queryForObject("SELECT DATABASE()",String.class)); }
    @AfterEach void clearFaults() { reset(orders,inventory,trades,catalog); }
    String key() { return UUID.randomUUID().toString(); }
    JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    JsonNode data(HttpResponse<String> response, int expected) { assertEquals(expected,response.statusCode(),response.body()); return body(response).path("data"); }
    HttpResponse<String> request(String method, String path, Object payload, String token, String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if (token!=null) builder.header("Authorization","Bearer "+token);
        if (key!=null) builder.header("Idempotency-Key",key);
        builder.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    Actor actor(boolean admin) throws Exception {
        String username="o_"+key().replace("-","").substring(0,20), password="Test_"+key();
        var credentials=Map.of("username",username,"password",password);
        long id=Long.parseLong(data(request("POST","/api/v1/auth/register",credentials,null,null),201).path("userId").asString());
        if (admin) db.update("UPDATE tf_user SET role='ADMIN' WHERE id=?",id);
        String token=data(request("POST","/api/v1/auth/login",credentials,null,null),200).path("accessToken").asString();
        return new Actor(id,token);
    }
    Fixture fixture(int capacity) {
        // Test-only preparation: catalog HTTP freeze rules prohibit constructing past sale windows.
        return new TransactionTemplate(manager).execute(status->{
            LocalDateTime now=trades.now();
            long event=catalog.createEvent("Trade_"+key(),"Show","music","Beijing","Hall");
            long session=catalog.createSession(event,now.plusHours(2),now.minusHours(1),now.plusHours(1));
            long tier=catalog.createTier(session,"Standard",58000,capacity), other=catalog.createTier(session,"VIP",88000,capacity);
            catalog.setStatus(event,"ON_SALE");
            return new Fixture(event,session,tier,other);
        });
    }
    HttpResponse<String> create(Actor actor, long tier, String key) throws Exception { return request("POST","/api/v1/orders",Map.of("tierId",Long.toString(tier),"quantity",1),actor.token(),key); }
    long count(String sql, Object... values) { return db.queryForObject(sql,Long.class,values); }
    void consistent(Fixture f, int expectedOrders) {
        assertEquals(expectedOrders,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));
        assertEquals(expectedOrders,count("SELECT COUNT(*) FROM tf_purchase_slot WHERE session_id=?",f.session()));
        assertEquals(expectedOrders,count("SELECT COUNT(*) FROM tf_stock_log l JOIN tf_order o ON o.id=l.order_id WHERE o.session_id=? AND l.movement='RESERVE'",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_stock s JOIN tf_tier t ON t.id=s.tier_id WHERE t.session_id=? AND (s.capacity<>s.available+s.reserved+s.sold OR s.reserved<>(SELECT COUNT(*) FROM tf_order o WHERE o.tier_id=t.id AND o.status='PENDING') OR s.sold<>0)",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_order o LEFT JOIN tf_purchase_slot p ON p.order_id=o.id AND p.user_id=o.user_id AND p.session_id=o.session_id WHERE o.session_id=? AND p.order_id IS NULL",f.session()));
    }
    void noRequest(Actor actor, String key) { assertEquals(0,count("SELECT COUNT(*) FROM tf_request WHERE user_id=? AND request_key=?",actor.id(),key)); }
    void rejected(HttpResponse<String> response, String code) { assertEquals(409,response.statusCode(),response.body()); assertEquals(code,body(response).path("code").asString()); }
    List<HttpResponse<String>> simultaneous(List<Callable<HttpResponse<String>>> tasks) throws Exception {
        var pool=Executors.newFixedThreadPool(tasks.size()); var start=new CountDownLatch(1);
        try {
            var pending=new ArrayList<Future<HttpResponse<String>>>();
            for (var task:tasks) pending.add(pool.submit(()->{start.await();return task.call();}));
            start.countDown(); var results=new ArrayList<HttpResponse<String>>();
            for (var future:pending) results.add(future.get(20,TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
    Connection hold(String table, String column, long id) throws Exception {
        var connection=source.getConnection(); connection.setAutoCommit(false);
        try (var statement=connection.prepareStatement("SELECT "+column+" FROM "+table+" WHERE "+column+"=? FOR UPDATE")) {
            statement.setLong(1,id); try (var result=statement.executeQuery()) { assertTrue(result.next()); }
        }
        return connection;
    }
    void awaitSql(String pattern) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (count("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE ?",pattern)>0) return;
            Thread.sleep(10);
        }
        fail("Competing SQL did not enter the database: "+pattern);
    }

    @Test void createSnapshotsExpiryAndOwnerQueriesSurviveCatalogChanges() throws Exception {
        var user=actor(false); var other=actor(false); var admin=actor(true); var f=fixture(2);
        db.update("UPDATE tf_session SET starts_at=UTC_TIMESTAMP(6)+INTERVAL 2 MINUTE,sale_end_at=UTC_TIMESTAMP(6)+INTERVAL 1 MINUTE WHERE id=?",f.session());
        String order=data(create(user,f.tier(),key()),201).path("orderId").asString();
        var detail=data(request("GET","/api/v1/orders/"+order,null,user.token(),null),200);
        assertEquals("PENDING",detail.path("status").asString()); assertEquals(1,detail.path("quantity").asInt());
        assertEquals(58000,detail.path("amountFen").asLong()); assertEquals(58000,detail.path("unitPriceFen").asLong());
        assertEquals(detail.path("snapshot").path("startsAt").asString(),detail.path("expiresAt").asString());
        assertTrue(detail.path("payment").isNull()); assertTrue(detail.path("refund").isNull());
        var snapshot=detail.path("snapshot"); assertEquals(13,snapshot.size()); assertEquals("1",snapshot.path("schemaVersion").asString());
        data(request("PUT","/api/v1/admin/events/"+f.event(),Map.of("name","Changed","description","New","category","music","city","Beijing","venue","Hall","expectedVersion",1),admin.token(),null),200);
        data(request("PUT","/api/v1/admin/events/"+f.event()+"/status",Map.of("status","OFF_SALE","expectedVersion",2),admin.token(),null),200);
        assertEquals(snapshot,data(request("GET","/api/v1/orders/"+order,null,user.token(),null),200).path("snapshot"));
        var page=data(request("GET","/api/v1/orders?status=PENDING&page=1&size=1",null,user.token(),null),200);
        assertEquals(1,page.path("total").asInt()); assertEquals(order,page.path("items").get(0).path("orderId").asString());
        assertEquals(0,data(request("GET","/api/v1/orders?status=PAID",null,user.token(),null),200).path("total").asInt());
        assertEquals(0,data(request("GET","/api/v1/orders?page=2&size=1",null,user.token(),null),200).path("items").size());
        var forbidden=request("GET","/api/v1/orders/"+order,null,other.token(),null);
        var missing=request("GET","/api/v1/orders/9223372036854775807",null,other.token(),null);
        assertEquals(404,forbidden.statusCode()); assertEquals(404,missing.statusCode());
        assertEquals(body(forbidden).path("code"),body(missing).path("code"));
        assertEquals(0,data(request("GET","/api/v1/orders",null,other.token(),null),200).path("total").asInt());
        consistent(f,1);
    }

    @Test void validationAndAuthenticationFailBeforeRequestPersistence() throws Exception {
        var user=actor(false); var f=fixture(2);
        var valid=Map.of("tierId",Long.toString(f.tier()),"quantity",1);
        assertEquals(401,request("POST","/api/v1/orders",valid,null,key()).statusCode());
        assertEquals(401,request("GET","/api/v1/orders",null,null,null).statusCode());
        for (String requestKey:new String[]{"short","a".repeat(65),"a".repeat(15)}) {
            assertEquals(400,request("POST","/api/v1/orders",valid,user.token(),requestKey).statusCode()); noRequest(user,requestKey);
        }
        assertEquals(400,request("POST","/api/v1/orders",valid,user.token(),null).statusCode());
        for (Object invalid:List.of(Map.of("tierId",Long.toString(f.tier()),"quantity",2),Map.of("tierId","0","quantity",1),
                Map.of("tierId",Long.toString(f.tier()),"quantity",1.5),Map.of("tierId",Long.toString(f.tier()),"quantity","1"),
                Map.of("tierId",f.tier(),"quantity",1),
                Map.of("tierId","01","quantity",1),Map.of("tierId",Long.toString(f.tier())),Map.of("tierId",Long.toString(f.tier()),"quantity",1,"amountFen",1),Map.of())) {
            String key=key(); assertEquals(400,request("POST","/api/v1/orders",invalid,user.token(),key).statusCode()); noRequest(user,key);
        }
        for (String suffix:List.of("?page=0","?size=101","?status=UNKNOWN","?page=2147483647&size=100","/01"))
            assertEquals(400,request("GET","/api/v1/orders"+suffix,null,user.token(),null).statusCode());
        String missingKey=key(); assertEquals(404,create(user,Long.MAX_VALUE,missingKey).statusCode());
        assertEquals("REJECTED",db.queryForObject("SELECT state FROM tf_request WHERE user_id=? AND request_key=?",String.class,user.id(),missingKey));
        consistent(f,0);
    }

    @Test void twentyDuplicateRequestsReplayOneOrderAndConflictOnChangedPayload() throws Exception {
        var user=actor(false); var f=fixture(2); String key=key();
        var tasks=new ArrayList<Callable<HttpResponse<String>>>();
        for (int i=0;i<20;i++) tasks.add(()->create(user,f.tier(),key));
        var responses=simultaneous(tasks); var ids=new HashSet<String>(); var traces=new HashSet<String>(); int replayed=0;
        for (var response:responses) {
            ids.add(data(response,201).path("orderId").asString());
            traces.add(body(response).path("traceId").asString());
            assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(),body(response).path("traceId").asString());
            if (body(response).path("replayed").asBoolean()) replayed++;
        }
        assertEquals(1,ids.size()); assertEquals(20,traces.size()); assertEquals(19,replayed);
        var detail=data(request("GET","/api/v1/orders/"+ids.iterator().next(),null,user.token(),null),200);
        assertEquals(Instant.parse(detail.path("createdAt").asString()).plusSeconds(900),Instant.parse(detail.path("expiresAt").asString()));
        rejected(create(user,f.otherTier(),key),"IDEMPOTENCY_CONFLICT");
        assertEquals(1,count("SELECT COUNT(*) FROM tf_request WHERE user_id=?",user.id()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_request WHERE user_id=? AND state='PROCESSING'",user.id()));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT state FROM tf_request WHERE user_id=? AND request_key=?",String.class,user.id(),key));
        // Discarded original response is recovered by the same key.
        assertEquals(ids.iterator().next(),data(create(user,f.tier(),key),201).path("orderId").asString());
        var another=actor(false); data(create(another,f.tier(),key),201); consistent(f,2);
    }

    @Test void concurrentDifferentKeysAcrossTiersEnforceSessionLimit() throws Exception {
        var user=actor(false); var f=fixture(3);
        var responses=simultaneous(List.of(()->create(user,f.tier(),key()),()->create(user,f.otherTier(),key())));
        assertEquals(Set.of(201,409),new HashSet<>(responses.stream().map(HttpResponse::statusCode).toList()));
        for (var response:responses) if (response.statusCode()==409) rejected(response,"PURCHASE_LIMIT");
        assertEquals(2,count("SELECT COUNT(*) FROM tf_request WHERE user_id=? AND state IN ('SUCCEEDED','REJECTED')",user.id()));
        consistent(f,1);
    }

    @Test void twoUsersCompeteForLastTicketWithoutOversell() throws Exception {
        var one=actor(false); var two=actor(false); var f=fixture(1);
        var responses=simultaneous(List.of(()->create(one,f.tier(),key()),()->create(two,f.tier(),key())));
        assertEquals(Set.of(201,409),new HashSet<>(responses.stream().map(HttpResponse::statusCode).toList()));
        for (var response:responses) if (response.statusCode()==409) rejected(response,"SOLD_OUT");
        assertEquals(0,count("SELECT available FROM tf_stock WHERE tier_id=?",f.tier())); consistent(f,1);
    }

    @Test void rejectedSaleResultsPersistAndReplayAfterConditionsChange() throws Exception {
        for (String code:List.of("NOT_ON_SALE","SALE_NOT_STARTED","SALE_ENDED","SOLD_OUT")) {
            var user=actor(false); var f=fixture(0); String key=key();
            if (code.equals("NOT_ON_SALE")) db.update("UPDATE tf_event SET status='OFF_SALE' WHERE id=?",f.event());
            if (code.equals("SALE_NOT_STARTED")) db.update("UPDATE tf_session SET sale_start_at=UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE WHERE id=?",f.session());
            if (code.equals("SALE_ENDED")) db.update("UPDATE tf_session SET sale_end_at=UTC_TIMESTAMP(6) WHERE id=?",f.session());
            rejected(create(user,f.tier(),key),code); consistent(f,0);
            assertEquals(1,count("SELECT COUNT(*) FROM tf_request WHERE user_id=? AND state='REJECTED' AND order_id IS NULL",user.id()));
            // Test-only change to demonstrate immutable rejection replay; release lifecycle is batch 4.
            db.update("UPDATE tf_event SET status='ON_SALE' WHERE id=?",f.event());
            db.update("UPDATE tf_session SET sale_start_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,sale_end_at=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR WHERE id=?",f.session());
            db.update("UPDATE tf_stock SET capacity=1,available=1 WHERE tier_id=?",f.tier());
            var replay=create(user,f.tier(),key); rejected(replay,code); assertTrue(body(replay).path("replayed").asBoolean());
            data(create(user,f.tier(),key()),201); consistent(f,1);
        }
    }

    @Test void realExecutorSavepointRevertsOrderSlotStockAndLogButCommitsRejection() throws Exception {
        var user=actor(false); var f=fixture(1); String key=key();
        doAnswer(call->{call.callRealMethod(); throw new BusinessRejection(409,"SOLD_OUT","测试拒绝");})
                .when(inventory).logReserve(anyLong(),any());
        rejected(create(user,f.tier(),key),"SOLD_OUT");
        reset(inventory); consistent(f,0); assertEquals(1,count("SELECT available FROM tf_stock WHERE tier_id=?",f.tier()));
        assertEquals(1,count("SELECT COUNT(*) FROM tf_request WHERE user_id=? AND state='REJECTED' AND order_id IS NULL AND completed_at IS NOT NULL",user.id()));
        var replay=create(user,f.tier(),key); rejected(replay,"SOLD_OUT"); assertTrue(body(replay).path("replayed").asBoolean());
        data(create(user,f.tier(),key()),201); consistent(f,1);
    }

    @Test void systemFailuresAtEveryWriteStageRollbackEntireTransactionAndSameKeyRecovers() throws Exception {
        for (int stage=0;stage<5;stage++) {
            var user=actor(false); var f=fixture(1); String key=key();
            if (stage==0) doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test order fault");}).when(orders).insert(anyLong(),any(),anyString(),any(),any());
            if (stage==1) doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test slot fault");}).when(orders).insertSlot(anyLong(),anyLong(),anyLong());
            if (stage==2) doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test stock fault");}).when(inventory).reserve(anyLong(),any());
            if (stage==3) doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test log fault");}).when(inventory).logReserve(anyLong(),any());
            if (stage==4) doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test result fault");}).when(trades).complete(anyLong(),anyString(),anyInt(),anyString(),anyString(),nullable(Long.class));
            var failure=create(user,f.tier(),key); assertEquals(500,failure.statusCode(),failure.body());
            assertEquals("INTERNAL_ERROR",body(failure).path("code").asString());
            reset(orders,inventory,trades); consistent(f,0); noRequest(user,key);
            data(create(user,f.tier(),key),201); consistent(f,1);
        }
    }

    @Test void unexpectedSqlConstraintFailureRollsBackAndIsNotBusinessRejection() throws Exception {
        var user=actor(false); var f=fixture(1); String key=key();
        doAnswer(call->{call.callRealMethod(); return call.callRealMethod();}).when(inventory).logReserve(anyLong(),any());
        var failure=create(user,f.tier(),key); assertEquals(503,failure.statusCode(),failure.body());
        assertTrue(body(failure).path("data").path("retryWithSameKey").asBoolean());
        reset(inventory); consistent(f,0); noRequest(user,key);
        data(create(user,f.tier(),key),201); consistent(f,1);
    }

    @Test void lockFailuresRetryWholeTransactionAtMostTwice() throws Exception {
        for (int mysqlCode:List.of(1205,1213)) {
            var user=actor(false); var f=fixture(1); String key=key(); var attempts=new AtomicInteger();
            doAnswer(call->{
                boolean enabled=(boolean)call.callRealMethod();
                assertEquals("READ-COMMITTED",db.queryForObject("SELECT @@transaction_isolation",String.class));
                if (attempts.incrementAndGet()<=2) {
                    db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",user.id());
                    throw new CannotAcquireLockException("controlled lock error",new SQLException("controlled","40001",mysqlCode));
                }
                return enabled;
            }).when(trades).lockUser(user.id());
            data(create(user,f.tier(),key),201); assertEquals(3,attempts.get()); reset(trades); consistent(f,1);
            var blocked=actor(false); String blockedKey=key(); var failures=new AtomicInteger();
            doAnswer(call->{failures.incrementAndGet(); throw new CannotAcquireLockException("controlled",new SQLException("controlled","40001",mysqlCode));}).when(trades).lockUser(blocked.id());
            assertEquals(503,create(blocked,f.otherTier(),blockedKey).statusCode()); assertEquals(3,failures.get());
            noRequest(blocked,blockedKey); reset(trades);
        }
    }

    @Test void saleTimeIsReadAfterWaitingForStockLock() throws Exception {
        for (boolean crossingEnd:List.of(true,false)) {
            var user=actor(false); var f=fixture(1); String key=key();
            LocalDateTime boundary=trades.now().plusNanos(900_000_000);
            db.update(crossingEnd?"UPDATE tf_session SET sale_end_at=? WHERE id=?":"UPDATE tf_session SET sale_start_at=? WHERE id=?",boundary.toString().replace('T',' '),f.session());
            var pool=Executors.newSingleThreadExecutor();
            try (var lock=hold("tf_stock","tier_id",f.tier())) {
                var future=pool.submit(()->create(user,f.tier(),key));
                awaitSql("SELECT available FROM tf_stock WHERE tier_id=%FOR UPDATE");
                while (trades.now().isBefore(boundary.plusNanos(100_000_000))) Thread.sleep(10);
                lock.commit(); var response=future.get(10,TimeUnit.SECONDS);
                if (crossingEnd) { rejected(response,"SALE_ENDED"); consistent(f,0); }
                else {
                    String order=data(response,201).path("orderId").asString();
                    assertTrue(!LocalDateTime.parse(db.queryForObject("SELECT created_at FROM tf_order WHERE id=?",String.class,Long.parseLong(order)).replace(' ','T')).isBefore(boundary));
                    consistent(f,1);
                }
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void offSaleHoldingEventLockWinsAgainstWaitingCreate() throws Exception {
        var admin=actor(true); var user=actor(false); var f=fixture(1);
        var pool=Executors.newFixedThreadPool(2);
        var adminHasLock=new CountDownLatch(1); var finishAdmin=new CountDownLatch(1);
        doAnswer(call->{var result=call.callRealMethod(); adminHasLock.countDown(); assertTrue(finishAdmin.await(3,TimeUnit.SECONDS)); return result;})
                .when(catalog).event(f.event(),true);
        try {
            var offSale=pool.submit(()->request("PUT","/api/v1/admin/events/"+f.event()+"/status",Map.of("status","OFF_SALE","expectedVersion",1),admin.token(),null));
            assertTrue(adminHasLock.await(3,TimeUnit.SECONDS));
            var future=pool.submit(()->create(user,f.tier(),key()));
            awaitSql("SELECT id FROM tf_event WHERE id=%FOR SHARE");
            finishAdmin.countDown(); data(offSale.get(10,TimeUnit.SECONDS),200);
            rejected(future.get(10,TimeUnit.SECONDS),"NOT_ON_SALE"); consistent(f,0);
        } finally { finishAdmin.countDown(); pool.shutdownNow(); }
    }
    // All fault hooks above are Mockito test bean overrides; no production switch or HTTP parameter.

    @Test void createHoldingSharedCatalogLockCommitsBeforeWaitingOffSale() throws Exception {
        var admin=actor(true); var user=actor(false); var f=fixture(1);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{var result=call.callRealMethod(); locked.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)); return result;})
                .when(orders).lockCatalog(f.tier());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var purchase=pool.submit(()->create(user,f.tier(),key())); assertTrue(locked.await(3,TimeUnit.SECONDS));
            var offSale=pool.submit(()->request("PUT","/api/v1/admin/events/"+f.event()+"/status",Map.of("status","OFF_SALE","expectedVersion",1),admin.token(),null));
            awaitSql("SELECT * FROM tf_event WHERE id=%FOR UPDATE");
            release.countDown(); data(purchase.get(10,TimeUnit.SECONDS),201); data(offSale.get(10,TimeUnit.SECONDS),200);
            reset(orders); var another=actor(false); rejected(create(another,f.tier(),key()),"NOT_ON_SALE"); consistent(f,1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void userDisabledWhileWaitingIsRecheckedInsideTransaction() throws Exception {
        var user=actor(false); var f=fixture(1); String key=key(); var pool=Executors.newSingleThreadExecutor();
        try (var lock=hold("tf_user","id",user.id())) {
            var pending=pool.submit(()->create(user,f.tier(),key));
            awaitSql("SELECT enabled FROM tf_user WHERE id=%FOR UPDATE");
            try (var statement=lock.prepareStatement("UPDATE tf_user SET enabled=FALSE WHERE id=?")) { statement.setLong(1,user.id()); statement.executeUpdate(); }
            lock.commit(); assertEquals(401,pending.get(10,TimeUnit.SECONDS).statusCode()); noRequest(user,key); consistent(f,0);
        } finally { pool.shutdownNow(); }
    }

    @Test void realLockTimeoutReturnsRetryableErrorWithoutCommittedProcessing() throws Exception {
        var user=actor(false); var f=fixture(1); String key=key();
        try (var lock=hold("tf_user","id",user.id())) {
            long began=System.nanoTime(); var response=create(user,f.tier(),key);
            assertEquals(503,response.statusCode(),response.body());
            assertTrue(body(response).path("data").path("retryWithSameKey").asBoolean());
            assertTrue(Duration.ofNanos(System.nanoTime()-began).toSeconds()<8);
            lock.rollback(); noRequest(user,key); consistent(f,0);
        }
        data(create(user,f.tier(),key),201); consistent(f,1);
    }
}
