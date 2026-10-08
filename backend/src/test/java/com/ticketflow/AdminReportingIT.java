package com.ticketflow;

import com.ticketflow.mapper.AdminOrderMapper;
import com.ticketflow.model.entity.AdminOrderFilter;
import com.ticketflow.service.OrderApplicationService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminReportingIT extends OrderTestSupport {
    @MockitoSpyBean AdminOrderMapper reporting;
    @Autowired OrderApplicationService service;
    @AfterEach void resetReporting() { reset(reporting); }
    long buy(Actor user,Fixture f) throws Exception { return Long.parseLong(data(create(user,f.tier(),key()),201).path("orderId").asString()); }
    void act(Actor user,long id,String operation) throws Exception { data(request("POST","/api/v1/orders/"+id+"/"+operation,Map.of(),user.token(),key()),200); }
    String stats(String from,String to) { return "/api/v1/admin/statistics?from="+from+"&to="+to; }
    @Test void managementAuthorizationAndValidationAreEnforced() throws Exception {
        var admin=actor(true); var user=actor(false);
        for (String path:List.of("/api/v1/admin/orders",stats("2040-01-01T00:00:00Z","2040-01-02T00:00:00Z"))) {
            assertEquals(401,request("GET",path,null,null,null).statusCode());
            assertEquals(403,request("GET",path,null,user.token(),null).statusCode());
            data(request("GET",path,null,admin.token(),null),200);
        }
        for (String query:List.of("orderId=0","orderId=9223372036854775808","sessionId=-1","status=paid","page=0","size=101","page=2147483647&size=100","from=2040-01-01T00:00:00","from=2040-01-02T00:00:00Z&to=2040-01-01T00:00:00Z","to=2040-01-01T00:00:00.0000001Z"))
            assertEquals(400,request("GET","/api/v1/admin/orders?"+query,null,admin.token(),null).statusCode(),query);
        for (String path:List.of("/api/v1/admin/statistics",stats("2040-01-01T00:00:00Z","2040-02-02T00:00:00Z"),stats("2040-01-01T00:00:00Z","2040-01-01T00:00:00Z")))
            assertEquals(400,request("GET",path,null,admin.token(),null).statusCode());
        data(request("GET",stats("2040-01-01T00:00:00Z","2040-02-01T00:00:00Z"),null,admin.token(),null),200);
        db.update("UPDATE tf_user SET role='USER' WHERE id=?",admin.id()); assertEquals(403,request("GET","/api/v1/admin/orders",null,admin.token(),null).statusCode());
        db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",admin.id()); assertEquals(401,request("GET","/api/v1/admin/orders",null,admin.token(),null).statusCode());
    }
    @Test void managementOrdersExposeOwnersSnapshotsAndLifecycleRecordsWithFilters() throws Exception {
        var admin=actor(true); var f=fixture(5); var ids=new ArrayList<Long>();
        for (String state:List.of("PENDING","PAID","CANCELLED","CLOSED","REFUNDED")) {
            var user=actor(false); long order=buy(user,f); ids.add(order);
            if (state.equals("PAID") || state.equals("REFUNDED")) act(user,order,"payments");
            if (state.equals("REFUNDED")) act(user,order,"refunds");
            if (state.equals("CANCELLED")) act(user,order,"cancel");
            if (state.equals("CLOSED")) { db.update("UPDATE tf_order SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE id=?",order); assertTrue(service.closeExpired(order)); }
            var item=data(request("GET","/api/v1/admin/orders?orderId="+order,null,admin.token(),null),200).path("items").get(0);
            assertEquals(state,item.path("status").asString()); assertEquals(Long.toString(user.id()),item.path("userId").asString());
            assertEquals(58000,item.path("snapshot").path("amountFen").asLong()); assertFalse(item.has("passwordHash"));
            assertEquals(state.equals("PAID") || state.equals("REFUNDED"),!item.path("payment").isNull()); assertEquals(state.equals("REFUNDED"),!item.path("refund").isNull());
        }
        String path="/api/v1/admin/orders?sessionId="+f.session();
        var page=data(request("GET",path+"&size=2&page=1",null,admin.token(),null),200); assertEquals(5,page.path("total").asLong()); assertEquals(ids.get(4).toString(),page.path("items").get(0).path("orderId").asString());
        assertEquals(1,data(request("GET",path+"&size=2&page=3",null,admin.token(),null),200).path("items").size());
        assertEquals(1,data(request("GET",path+"&status=PAID",null,admin.token(),null),200).path("total").asLong());
        assertEquals(0,data(request("GET",path+"&orderId="+Long.MAX_VALUE,null,admin.token(),null),200).path("total").asLong());
        long id=ids.get(0); String created=db.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM tf_order WHERE id=?",String.class,id);
        assertEquals(1,data(request("GET",path+"&orderId="+id+"&from="+created,null,admin.token(),null),200).path("total").asLong());
        assertEquals(0,data(request("GET",path+"&orderId="+id+"&to="+created,null,admin.token(),null),200).path("total").asLong());
    }
    @Test void statisticsUseIndependentHalfOpenTimeWindowsAndCanHaveNegativeNet() throws Exception {
        var admin=actor(true); var user=actor(false); var f=fixture(1); long order=buy(user,f); act(user,order,"payments"); act(user,order,"refunds");
        // White-box historical timestamps isolate this boundary test from all other test runs.
        var start=LocalDate.of(2100,1,1).plusDays(order*3); String day=start.toString(),next=start.plusDays(1).toString();
        db.update("UPDATE tf_order SET created_at=?,expires_at=?,starts_at=? WHERE id=?",day+" 00:00:00",day+" 00:15:00",next+" 00:00:00",order);
        db.update("UPDATE tf_payment SET paid_at=? WHERE order_id=?",day+" 00:00:00",order);
        db.update("UPDATE tf_refund SET refunded_at=? WHERE order_id=?",next+" 00:00:00",order);
        var first=data(request("GET",stats(day+"T00:00:00Z",next+"T00:00:00Z"),null,admin.token(),null),200);
        assertEquals(1,first.path("orderCount").asLong()); assertEquals(58000,first.path("paidAmountFen").asLong()); assertEquals(0,first.path("refundAmountFen").asLong()); assertEquals(58000,first.path("netAmountFen").asLong());
        assertEquals("created_at",first.path("orderTimeBasis").asString()); assertEquals("paid_at",first.path("paymentTimeBasis").asString()); assertEquals("refunded_at",first.path("refundTimeBasis").asString());
        var second=data(request("GET",stats(next+"T00:00:00Z",start.plusDays(2)+"T00:00:00Z"),null,admin.token(),null),200);
        assertEquals(0,second.path("orderCount").asLong()); assertEquals(0,second.path("paidAmountFen").asLong()); assertEquals(58000,second.path("refundAmountFen").asLong()); assertEquals(-58000,second.path("netAmountFen").asLong());
    }
    @Test void statisticsKeepOneSnapshotWhenRefundCommitsBetweenAggregates() throws Exception {
        var admin=actor(true); var user=actor(false); var f=fixture(1); long order=buy(user,f); act(user,order,"payments");
        String day=LocalDate.of(2100,1,1).plusDays(order*3).toString(),next=LocalDate.parse(day).plusDays(1).toString();
        db.update("UPDATE tf_order SET created_at=? ,expires_at=?,starts_at=? WHERE id=?",day+" 00:00:00",day+" 00:15:00",next+" 00:00:00",order);
        db.update("UPDATE tf_payment SET paid_at=? WHERE order_id=?",day+" 00:01:00",order);
        // Commit a refund from another connection after the first aggregate establishes the snapshot.
        doAnswer(call->{ Object result=call.callRealMethod(); var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try { pool.submit(()->{ act(user,order,"refunds"); db.update("UPDATE tf_refund SET refunded_at=? WHERE order_id=?",day+" 00:02:00",order); return true; }).get(10,java.util.concurrent.TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); } return result; }).when(reporting).orderCount(any(),any());
        String path=stats(day+"T00:00:00Z",next+"T00:00:00Z");
        var before=data(request("GET",path,null,admin.token(),null),200); assertEquals(58000,before.path("netAmountFen").asLong());
        reset(reporting); var after=data(request("GET",path,null,admin.token(),null),200); assertEquals(0,after.path("netAmountFen").asLong());
    }
    @Test void orderPageKeepsOneSnapshotBetweenRowsAndCount() throws Exception {
        var admin=actor(true); var user=actor(false); var f=fixture(1); long order=buy(user,f);
        doAnswer(call->{ Object result=call.callRealMethod(); act(user,order,"cancel"); return result; }).when(reporting).list(any(AdminOrderFilter.class),anyInt(),anyInt());
        String path="/api/v1/admin/orders?sessionId="+f.session()+"&status=PENDING";
        var before=data(request("GET",path,null,admin.token(),null),200); assertEquals(1,before.path("total").asLong()); assertEquals("PENDING",before.path("items").get(0).path("status").asString());
        reset(reporting); assertEquals(0,data(request("GET",path,null,admin.token(),null),200).path("total").asLong());
    }
    List<Map<String,Object>> reconcile(long tier) throws Exception {
        var path=Path.of("../scripts/reconcile.sql"); if (!Files.exists(path)) path=Path.of("scripts/reconcile.sql");
        String sql=Files.readString(path); sql=sql.substring(sql.indexOf("WITH scoped_orders"),sql.lastIndexOf("COMMIT;"));
        var tx=new TransactionTemplate(manager); tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        final String query=sql; return tx.execute(status->{db.execute("SET @tf_reconcile_tier="+tier);return db.queryForList(query);});
    }
    @Test void readOnlyReconciliationFindsCorruptionAndAcceptsEveryLifecycleState() throws Exception {
        var f=fixture(5); var actors=new ArrayList<Actor>(); var ids=new ArrayList<Long>();
        for (int i=0;i<5;i++) { var user=actor(false); actors.add(user); ids.add(buy(user,f)); }
        act(actors.get(1),ids.get(1),"payments"); act(actors.get(2),ids.get(2),"cancel");
        db.update("UPDATE tf_order SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE id=?",ids.get(3)); service.closeExpired(ids.get(3));
        act(actors.get(4),ids.get(4),"payments"); act(actors.get(4),ids.get(4),"refunds"); assertTrue(reconcile(f.tier()).isEmpty());
        long pending=ids.get(0),paid=ids.get(1);
        db.update("UPDATE tf_stock SET available=available-1,reserved=reserved+1 WHERE tier_id=?",f.tier());
        try { assertTrue(reconcile(f.tier()).stream().anyMatch(r->r.get("category").equals("STOCK_BALANCE"))); }
        finally { db.update("UPDATE tf_stock SET available=available+1,reserved=reserved-1 WHERE tier_id=?",f.tier()); }
        db.update("DELETE FROM tf_purchase_slot WHERE order_id=?",pending);
        try { assertTrue(reconcile(f.tier()).stream().anyMatch(r->r.get("category").equals("PURCHASE_SLOT"))); }
        finally { db.update("INSERT INTO tf_purchase_slot(user_id,session_id,order_id) VALUES(?,?,?)",actors.get(0).id(),f.session(),pending); }
        db.update("UPDATE tf_payment SET amount_fen=1 WHERE order_id=?",paid);
        try { assertTrue(reconcile(f.tier()).stream().anyMatch(r->r.get("category").equals("PAYMENT_REFUND"))); }
        finally { db.update("UPDATE tf_payment SET amount_fen=58000 WHERE order_id=?",paid); }
        db.update("UPDATE tf_stock_log SET movement='RELEASE',delta_available=1,delta_reserved=-1 WHERE order_id=? AND movement='RESERVE'",pending);
        try { assertTrue(reconcile(f.tier()).stream().anyMatch(r->r.get("category").equals("STOCK_LOG"))); }
        finally { db.update("UPDATE tf_stock_log SET movement='RESERVE',delta_available=-1,delta_reserved=1 WHERE order_id=? AND movement='RELEASE'",pending); }
        db.update("UPDATE tf_order SET snapshot=JSON_SET(snapshot,'$.amountFen',1) WHERE id=?",pending);
        try { assertTrue(reconcile(f.tier()).stream().anyMatch(r->r.get("category").equals("ORDER_SNAPSHOT"))); }
        finally { db.update("UPDATE tf_order SET snapshot=JSON_SET(snapshot,'$.amountFen',58000) WHERE id=?",pending); }
        assertTrue(reconcile(f.tier()).isEmpty());
        String completed=db.queryForObject("SELECT completed_at FROM tf_request WHERE order_id=? AND operation='CREATE'",String.class,pending);
        db.update("UPDATE tf_request SET state='PROCESSING',completed_at=NULL WHERE order_id=? AND operation='CREATE'",pending);
        try { assertTrue(reconcile(f.tier()).stream().anyMatch(r->r.get("category").equals("REQUEST_TERMINAL"))); }
        finally { db.update("UPDATE tf_request SET state='SUCCEEDED',completed_at=? WHERE order_id=? AND operation='CREATE'",completed,pending); }
        assertTrue(reconcile(f.tier()).isEmpty());
    }
    @Test void reconciliationUsesOneSnapshotWhileCancellationCommits() throws Exception {
        var user=actor(false); var f=fixture(1); long order=buy(user,f);
        var tx=new TransactionTemplate(manager); tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(status->{
            assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE id=? AND status='PENDING'",order));
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                pool.submit(()->{act(user,order,"cancel");return true;}).get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(reconcile(f.tier()).isEmpty());
                assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE id=? AND status='PENDING'",order));
            } catch (Exception e) { throw new RuntimeException(e); } finally { pool.shutdownNow(); }
            return null;
        });
        assertTrue(reconcile(f.tier()).isEmpty()); assertEquals("CANCELLED",db.queryForObject("SELECT status FROM tf_order WHERE id=?",String.class,order));
    }
    @Test void offSalePreservesExistingLifecycleAndManagementSnapshots() throws Exception {
        var admin=actor(true); var user=actor(false); var other=actor(false); var newcomer=actor(false); var f=fixture(2);
        long paid=buy(user,f),cancelled=buy(other,f);
        var snapshot=data(request("GET","/api/v1/orders/"+paid,null,user.token(),null),200).path("snapshot");
        data(request("PUT","/api/v1/admin/events/"+f.event(),Map.of("name","Edited copy","description","Changed","category","music","city","Beijing","venue","Hall","expectedVersion",1),admin.token(),null),200);
        data(request("PUT","/api/v1/admin/events/"+f.event()+"/status",Map.of("status","OFF_SALE","expectedVersion",2),admin.token(),null),200);
        rejected(create(newcomer,f.tier(),key()),"NOT_ON_SALE");
        act(user,paid,"payments"); act(other,cancelled,"cancel"); act(user,paid,"refunds");
        var detail=data(request("GET","/api/v1/admin/orders?orderId="+paid,null,admin.token(),null),200).path("items").get(0);
        assertEquals("REFUNDED",detail.path("status").asString()); assertEquals(snapshot,detail.path("snapshot")); assertFalse(detail.path("payment").isNull()); assertFalse(detail.path("refund").isNull());
        assertTrue(reconcile(f.tier()).isEmpty());
    }
    @Test void localSecurityDoesNotExposeManagementEndpointsOrAllowCrossOriginAccess() throws Exception {
        var admin=actor(true);
        for (String endpoint:List.of("/actuator/env","/actuator/configprops","/actuator/beans")) {
            assertEquals(401,request("GET",endpoint,null,null,null).statusCode());
            assertEquals(404,request("GET",endpoint,null,admin.token(),null).statusCode());
        }
        var cross=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/events"))
                .header("Origin","https://untrusted.example").GET().build();
        var response=http.send(cross,java.net.http.HttpResponse.BodyHandlers.ofString()); assertEquals(200,response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
    @Test void soldOutRejectionStaysImmutableAfterRealCancellationReleasesStock() throws Exception {
        var owner=actor(false); var buyer=actor(false); var f=fixture(1); long order=buy(owner,f); String rejectedKey=key();
        rejected(create(buyer,f.tier(),rejectedKey),"SOLD_OUT"); act(owner,order,"cancel");
        var replay=create(buyer,f.tier(),rejectedKey); rejected(replay,"SOLD_OUT"); assertTrue(body(replay).path("replayed").asBoolean());
        buy(buyer,f); assertTrue(reconcile(f.tier()).isEmpty());
    }
    @Test void databaseRejectsDuplicateTransactionsAndIncorrectCompositeOwnership() throws Exception {
        var user=actor(false); var f=fixture(1); var other=fixture(1); long order=buy(user,f); act(user,order,"payments"); act(user,order,"refunds");
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO tf_payment(order_id,amount_fen,paid_at) VALUES(?,58000,UTC_TIMESTAMP(6))",order));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO tf_refund(order_id,amount_fen,refunded_at) VALUES(?,58000,UTC_TIMESTAMP(6))",order));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO tf_request(user_id,operation,request_key,payload_hash,state,http_status,result_code,result_json,order_id,created_at,completed_at) SELECT user_id,operation,request_key,payload_hash,state,http_status,result_code,result_json,order_id,created_at,completed_at FROM tf_request WHERE order_id=? AND operation='CREATE'",order));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO tf_purchase_slot(user_id,session_id,order_id) VALUES(?,?,?)",user.id(),other.session(),order));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO tf_order(user_id,session_id,tier_id,status,quantity,unit_price_fen,amount_fen,snapshot,starts_at,expires_at,created_at,updated_at) SELECT user_id,?,tier_id,status,quantity,unit_price_fen,amount_fen,snapshot,starts_at,expires_at,created_at,updated_at FROM tf_order WHERE id=?",other.session(),order));
        assertTrue(reconcile(f.tier()).isEmpty());
    }
    @Test void unchangedMigrationBuildsAnEmptyTableNamespaceAndDoesNotRepeat() throws Exception {
        String prefix="b5_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_";
        assertTrue(prefix.matches("b5_[a-f0-9]{12}_"));
        Path migration=Path.of("src/main/resources/db/migration/V1__init_schema.sql");
        if (!Files.exists(migration)) migration=Path.of("backend/src/main/resources/db/migration/V1__init_schema.sql");
        Path folder=Files.createTempDirectory("ticketflow-empty-namespace-"); Path script=folder.resolve("V1__init_schema.sql");
        String ddl=Files.readString(migration); Files.writeString(script,ddl.replace("tf_",prefix+"tf_"));
        Path revisionScript=folder.resolve("V2__catalog_revision.sql");
        Files.writeString(revisionScript,Files.readString(migration.resolveSibling("V2__catalog_revision.sql")).replace("tf_",prefix+"tf_"));
        Path asyncScript=folder.resolve("V3__async_purchase.sql");
        // MySQL CHECK names are schema-wide, so isolated tables need isolated names too.
        Files.writeString(asyncScript,Files.readString(migration.resolveSibling("V3__async_purchase.sql"))
                .replace("tf_",prefix+"tf_").replace("ck_session_purchase_mode",prefix+"ck_mode"));
        Path recoveryScript=folder.resolve("V4__async_recovery_operations.sql");
        Files.writeString(recoveryScript,Files.readString(migration.resolveSibling("V4__async_recovery_operations.sql")).replace("tf_",prefix+"tf_"));
        // Dedicated test database: only newly generated names are used. The
        // least-privilege account has no DROP permission, so retain the empty
        // namespace for inspection, just like other append-only test fixtures.
        try {
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(source).table(prefix+"history")
                    .locations("filesystem:"+folder).baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
            assertEquals(4,flyway.migrate().migrationsExecuted);
            assertEquals(20,count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,?)=?",prefix.length()+3,prefix+"tf_"));
            assertEquals(0,count("SELECT revision FROM "+prefix+"tf_catalog_revision WHERE id=1"));
            assertEquals(0,flyway.migrate().migrationsExecuted); assertTrue(flyway.validateWithResult().validationSuccessful);
        } finally {
            assertEquals("ticketflow_test",db.queryForObject("SELECT DATABASE()",String.class));
            Files.deleteIfExists(script); Files.deleteIfExists(revisionScript); Files.deleteIfExists(asyncScript); Files.deleteIfExists(recoveryScript); Files.deleteIfExists(folder);
        }
    }
}
