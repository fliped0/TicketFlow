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
abstract class OrderTestSupport {
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

}
