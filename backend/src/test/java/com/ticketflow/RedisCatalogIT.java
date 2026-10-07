package com.ticketflow;

import com.ticketflow.config.RedisFeatureProperties;
import com.ticketflow.config.RedisGateway;
import com.ticketflow.model.dto.CatalogDTO;
import com.ticketflow.service.CatalogCacheService;
import com.ticketflow.service.CatalogService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="TF_REDIS_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ticketflow.redis.enabled=true", "ticketflow.redis.cache-ttl-seconds=30",
        "ticketflow.redis.empty-ttl-seconds=10", "ticketflow.redis.query-concurrency=8",
        "ticketflow.redis.merge-wait-millis=2000", "ticketflow.redis.user-limit=2",
        "ticketflow.redis.session-limit=2", "ticketflow.redis.window-millis=2000"})
class RedisCatalogIT extends OrderTestSupport {
    static final String PREFIX="tf:test:it_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void namespace(DynamicPropertyRegistry properties) {
        properties.add("ticketflow.redis.namespace",()->PREFIX);
    }
    @Autowired StringRedisTemplate redis;
    @Autowired CatalogService service;
    @Autowired CatalogCacheService cache;
    @Autowired RedisFeatureProperties settings;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean RedisGateway gateway;

    Set<String> keys() {
        var keys=new HashSet<String>();
        try (var cursor=redis.scan(ScanOptions.scanOptions().match(PREFIX+":*").count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }
    @BeforeEach void redisReady() {
        try (var connection=redis.getConnectionFactory().getConnection()) { assertEquals("PONG",connection.ping()); }
        var own=keys(); if (!own.isEmpty()) redis.delete(own);
        reset(gateway);
    }
    @AfterEach void cleanRedis() {
        reset(gateway);
        var own=keys(); if (!own.isEmpty()) redis.delete(own);
        assertTrue(keys().isEmpty());
    }
    java.net.http.HttpResponse<String> get(String path) throws Exception { return request("GET",path,null,null,null); }
    String eventPath(Fixture f) { return "/api/v1/events/"+f.event(); }

    @Test void cacheHitAndFilterPageKeysAvoidRepeatedCatalogSql() throws Exception {
        var f=fixture(2); String keyword=db.queryForObject("SELECT name FROM tf_event WHERE id=?",String.class,f.event());
        clearInvocations(catalog);
        String path="/api/v1/events?keyword="+keyword+"&city=Beijing&category=music&size=1";
        assertEquals(1,data(get(path),200).path("items").size());
        assertEquals(1,data(get(path),200).path("items").size());
        verify(catalog,times(1)).events(eq("ON_SALE"),anyString(),eq("Beijing"),eq("music"),eq(1),eq(0));
        assertEquals(0,data(get(path+"&page=2"),200).path("items").size());
        assertEquals(0,data(get(path.replace("Beijing","Shanghai")),200).path("items").size());
        assertEquals(3,keys().size());
    }
    @Test void negativeCacheExpiresAndPublicationChangesItsGeneration() throws Exception {
        var admin=actor(true);
        var draft=service.createEvent(admin.id(),new CatalogDTO.Event("Negative_"+key(),"Show","music","Beijing","Hall"));
        long id=Long.parseLong(draft.id()); clearInvocations(catalog);
        assertEquals(404,get("/api/v1/events/"+id).statusCode());
        assertEquals(404,get("/api/v1/events/"+id).statusCode());
        verify(catalog,times(1)).event(id,false);
        String negativeKey=keys().iterator().next();
        assertEquals("null",redis.opsForValue().get(negativeKey));
        // Expire this key explicitly: remote round trips must not exhaust the
        // cache lifetime before the independent cache-hit assertions finish.
        assertTrue(redis.expire(negativeKey,java.time.Duration.ofMillis(100)));
        Thread.sleep(150);
        assertNull(redis.opsForValue().get(negativeKey));
        assertEquals(404,get("/api/v1/events/"+id).statusCode());
        verify(catalog,times(2)).event(id,false);
        var now=java.time.Instant.now();
        var session=service.createSession(admin.id(),id,new CatalogDTO.Session(now.plusSeconds(7200).toString(),now.plusSeconds(3600).toString(),now.plusSeconds(5400).toString()));
        service.createTier(admin.id(),Long.parseLong(session.id()),new CatalogDTO.Tier("Standard",58000L,2));
        service.setStatus(admin.id(),id,new CatalogDTO.Status("ON_SALE",draft.version()));
        assertEquals(200,get("/api/v1/events/"+id).statusCode());
    }
    @Test void inventoryAndClockRemainLiveAfterMetadataCacheHit() throws Exception {
        var f=fixture(1); var user=actor(false);
        String tiers="/api/v1/sessions/"+f.session()+"/tiers";
        String sessions=eventPath(f)+"/sessions";
        data(get(tiers),200); data(get(sessions),200); clearInvocations(catalog);
        data(create(user,f.tier(),key()),201);
        var items=data(get(tiers),200).path("items");
        assertEquals(0,items.get(0).path("available").asInt());
        assertEquals("SOLD_OUT",items.get(0).path("saleStatus").asString());
        assertEquals(1,items.get(1).path("available").asInt());
        doReturn(catalog.now().plusHours(3)).when(catalog).now();
        assertEquals("SALE_ENDED",data(get(tiers),200).path("items").get(1).path("saleStatus").asString());
        assertEquals("SALE_ENDED",data(get(sessions),200).path("items").get(0).path("saleStatus").asString());
        verify(catalog,never()).pageTiers(anyLong(),anyInt(),anyInt());
        verify(catalog,never()).pageSessions(anyLong(),anyInt(),anyInt());
    }
    @Test void committedEditInvalidatesButRolledBackEditDoesNot() throws Exception {
        var admin=actor(true); var f=fixture(2);
        var old=catalog.event(f.event(),false); data(get(eventPath(f)),200);
        long revision=catalog.revision();
        new TransactionTemplate(manager).executeWithoutResult(status->{
            service.updateEvent(admin.id(),f.event(),new CatalogDTO.EventUpdate("RolledBack","Show","music","Beijing","Hall",old.version()));
            status.setRollbackOnly();
        });
        assertEquals(revision,catalog.revision());
        assertEquals(old.name(),data(get(eventPath(f)),200).path("name").asString());
        service.updateEvent(admin.id(),f.event(),new CatalogDTO.EventUpdate("Committed","Show","music","Beijing","Hall",old.version()));
        assertEquals(revision+1,catalog.revision());
        assertEquals("Committed",data(get(eventPath(f)),200).path("name").asString());
        assertEquals(200,request("GET","/api/v1/admin/events/"+f.event(),null,admin.token(),null).statusCode());
    }
    @Test void lateOldSnapshotCannotRefillNewGenerationAfterDelisting() throws Exception {
        var admin=actor(true); var f=fixture(2);
        var loaded=new CountDownLatch(1); var release=new CountDownLatch(1);
        var once=new AtomicInteger();
        doAnswer(call->{
            Object value=call.callRealMethod();
            if (once.getAndIncrement()==0) { loaded.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
            return value;
        }).when(catalog).event(f.event(),false);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->get(eventPath(f)));
            assertTrue(loaded.await(5,TimeUnit.SECONDS));
            var old=catalog.event(f.event(),true);
            service.setStatus(admin.id(),f.event(),new CatalogDTO.Status("OFF_SALE",old.version()));
            release.countDown(); assertEquals(200,pending.get(5,TimeUnit.SECONDS).statusCode());
            assertEquals(404,get(eventPath(f)).statusCode());
            assertEquals(404,get(eventPath(f)).statusCode());
            assertEquals(404,get(eventPath(f)+"/sessions").statusCode());
            assertEquals(404,get("/api/v1/sessions/"+f.session()+"/tiers").statusCode());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void sameHotMissIsMergedAndNoRedisCallRunsInTransaction() throws Exception {
        var f=fixture(1); clearInvocations(catalog);
        doAnswer(call->{ assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); })
                .when(gateway).get(anyString());
        doAnswer(call->{ assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); })
                .when(gateway).put(anyString(),anyString(),anyInt());
        var tasks=new ArrayList<Callable<java.net.http.HttpResponse<String>>>();
        for (int i=0;i<6;i++) tasks.add(()->get(eventPath(f)));
        for (var response:simultaneous(tasks)) assertEquals(200,response.statusCode(),response.body());
        verify(catalog,times(1)).event(f.event(),false);
    }
    @Test void saturatedQueryBudgetRejectsWithoutUnboundedDatabaseFallback() throws Exception {
        var f=fixture(1); var entered=new CountDownLatch(8); var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8); var tasks=new ArrayList<Future<?>>();
        try {
            for (int i=0;i<8;i++) tasks.add(pool.submit(()->cache.query(()->{
                entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new RuntimeException(error); } return true;
            })));
            assertTrue(entered.await(3,TimeUnit.SECONDS)); clearInvocations(catalog);
            var response=get(eventPath(f)); assertEquals(503,response.statusCode());
            assertEquals("CATALOG_BUSY",body(response).path("code").asString());
            verify(catalog,never()).event(anyLong(),anyBoolean());
        } finally { release.countDown(); for (var task:tasks) task.get(5,TimeUnit.SECONDS); pool.shutdownNow(); }
    }
    @Test void corruptPayloadIsReplacedFromDatabase() throws Exception {
        var f=fixture(1); data(get(eventPath(f)),200);
        String cacheKey=keys().iterator().next(); redis.opsForValue().set(cacheKey,"{broken");
        assertEquals(Long.toString(f.event()),data(get(eventPath(f)),200).path("id").asString());
        assertTrue(redis.opsForValue().get(cacheKey).contains("\"name\""));
    }
    @Test void cacheWriteFailureAfterCommitCannotMakeOldGenerationVisible() throws Exception {
        var admin=actor(true); var f=fixture(1); var before=catalog.event(f.event(),false);
        data(get(eventPath(f)),200);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test-only failure"))
                .when(gateway).put(anyString(),anyString(),anyInt());
        service.updateEvent(admin.id(),f.event(),new CatalogDTO.EventUpdate("AfterFailure","Show","music","Beijing","Hall",before.version()));
        assertEquals("AfterFailure",data(get(eventPath(f)),200).path("name").asString());
        reset(gateway); Thread.sleep(1100);
        assertEquals("AfterFailure",data(get(eventPath(f)),200).path("name").asString());
    }
    @Test void userLimitDoesNotConsumeRejectedKeyOrBlockReplayAndQuery() throws Exception {
        var a=fixture(2); var b=fixture(2); var c=fixture(2); var user=actor(false); String first=key();
        var one=data(create(user,a.tier(),first),201); data(create(user,b.tier(),key()),201);
        String throttled=key(); var limited=create(user,c.tier(),throttled);
        assertEquals(429,limited.statusCode(),limited.body());
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())>=1);
        noRequest(user,throttled);
        var replay=create(user,a.tier(),first); data(replay,201); assertTrue(body(replay).path("replayed").asBoolean());
        rejected(create(user,b.tier(),first),"IDEMPOTENCY_CONFLICT");
        assertEquals(200,request("GET","/api/v1/orders/"+one.path("orderId").asString(),null,user.token(),null).statusCode());
        Thread.sleep(2100); data(create(user,c.tier(),throttled),201);
        assertTrue(metrics.counter("ticketflow.purchase.limit","outcome","rejected").count()>0);
    }
    @Test void sessionLimitIsAtomicAcrossUsersAndTiers() throws Exception {
        doAnswer(call->{ assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); })
                .when(gateway).limit(anyList(),anyInt(),anyInt(),anyInt());
        var f=fixture(8); var actors=new ArrayList<Actor>();
        for (int i=0;i<6;i++) actors.add(actor(false));
        var tasks=new ArrayList<Callable<java.net.http.HttpResponse<String>>>();
        for (int i=0;i<6;i++) { var user=actors.get(i); long tier=i%2==0?f.tier():f.otherTier(); tasks.add(()->create(user,tier,key())); }
        var responses=simultaneous(tasks);
        assertEquals(2,responses.stream().filter(r->r.statusCode()==201).count());
        assertEquals(4,responses.stream().filter(r->r.statusCode()==429).count());
        consistent(f,2);
    }
    @Test void dualQuotaRejectionDoesNotChargeOtherDimensionAndRetryDoesNotRecharge() {
        String p=PREFIX+":{purchase-limit}:manual:";
        var first=List.of(p+"u1",p+"s1",p+"a1");
        assertEquals(0,gateway.limit(first,1,1,2000));
        assertEquals(-1,gateway.limit(first,1,1,2000));
        assertTrue(gateway.limit(List.of(p+"u2",p+"s1",p+"a2"),1,1,2000)>0);
        assertNull(redis.opsForValue().get(p+"u2"));
        assertEquals(0,gateway.limit(List.of(p+"u2",p+"s2",p+"a2"),1,1,2000));
        assertEquals("1",redis.opsForValue().get(p+"u1"));
    }
    @Test void configuredPositiveTtlExpiresAndReloads() throws Exception {
        var f=fixture(1); data(get(eventPath(f)),200); clearInvocations(catalog);
        String cacheKey=keys().iterator().next();
        long ttl=redis.getExpire(cacheKey,TimeUnit.MILLISECONDS);
        assertTrue(ttl>0 && ttl<=TimeUnit.SECONDS.toMillis(settings.cacheTtlSeconds()));
        verify(gateway).put(eq(cacheKey),anyString(),eq(settings.cacheTtlSeconds()));
        data(get(eventPath(f)),200); verify(catalog,never()).event(f.event(),false);
        assertTrue(redis.expire(cacheKey,java.time.Duration.ofMillis(100)));
        Thread.sleep(150); assertNull(redis.opsForValue().get(cacheKey));
        data(get(eventPath(f)),200); verify(catalog,times(1)).event(f.event(),false);
        assertEquals(30,settings.cacheTtlSeconds());
    }
}
