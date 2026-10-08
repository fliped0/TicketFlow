package com.ticketflow;

import com.rabbitmq.client.*;
import com.ticketflow.config.*;
import com.ticketflow.mapper.*;
import com.ticketflow.model.entity.*;
import com.ticketflow.service.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL/Redis/RabbitMQ; modest concurrency is a correctness check, not a capacity claim. */
@EnabledIfEnvironmentVariable(named="TF_ASYNC_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncPurchaseIT extends OrderTestSupport {
    static final String scope=UUID.randomUUID().toString().replace("-","").substring(0,12);
    static final String vhost="/ticketflow-async-"+scope,user="tf_async_"+scope,prefix="tf.test.async_"+scope;
    static final String pass=UUID.randomUUID().toString()+UUID.randomUUID();
    @DynamicPropertySource static void asyncSettings(DynamicPropertyRegistry p) {
        p.add("ticketflow.async.enabled",()->true);p.add("ticketflow.async.jobs-enabled",()->false);
        p.add("ticketflow.async.username",()->user);p.add("ticketflow.async.password",()->pass);
        p.add("ticketflow.async.vhost",()->vhost);p.add("ticketflow.async.broker-prefix",()->prefix);
        p.add("ticketflow.async.query-limit",()->2);
        // Retain the DB's namespace across retries/restarts; no FLUSHDB or foreign key deletion.
        p.add("ticketflow.redis.namespace",()->"tf:test:v1");
        p.add("ticketflow.redis.enabled",()->false);
        p.add("ticketflow.redis.window-millis",()->60000);
        p.add("ticketflow.redis.session-limit",()->1000);
    }
    @Autowired StringRedisTemplate redis;
    @Autowired AsyncGateMapper gates;
    @Autowired AsyncOrderService worker;
    @Autowired OrderApplicationService lifecycle;
    @Autowired OutboxPublisher publisher;
    @MockitoSpyBean AsyncRequestMapper async;
    @MockitoSpyBean OutboxMapper outbox;
    @MockitoSpyBean AsyncRedisGateway lua;
    @MockitoSpyBean AsyncBrokerGateway broker;
    @MockitoSpyBean DatabaseClock clock;
    @Autowired PurchaseRequestService purchases;
    @Autowired AsyncRecoveryService recovery;
    @MockitoSpyBean AsyncRecoveryMapper recoveryMapper;
    @Autowired AsyncDeadReplayService deadReplay;
    @Autowired AsyncReconciliationService reconciliation;
    @Autowired AsyncOperationsMapper operations;
    final List<Fixture> fixtures=new ArrayList<>();
    Actor admin;
    String enc(){return URLEncoder.encode(vhost,StandardCharsets.UTF_8);}
    void management(String method,String path,Object payload,int expected)throws Exception {
        String basic=Base64.getEncoder().encodeToString(("tf_admin:"+System.getenv("TF_RABBITMQ_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:15672/api/"+path)).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10)).header("Authorization","Basic "+basic).header("Content-Type","application/json")
                .method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()==expected || method.equals("DELETE")&&response.statusCode()==404,"Management "+method+" "+path+": "+response.statusCode());
    }
    @BeforeAll void prepare()throws Exception {
        admin=actor(true);
        management("PUT","vhosts/"+enc(),Map.of(),201);
        management("PUT","users/"+user,Map.of("password",pass,"tags",""),201);
        String pattern="^tf\\.test\\.async_"+scope+"\\..*";
        management("PUT","permissions/"+enc()+"/"+user,Map.of("configure",pattern,"write",pattern,"read",pattern),201);
        management("PUT","policies/"+enc()+"/async-dlx",Map.of("pattern",pattern,"priority",1,"apply-to","queues",
                "definition",Map.of("dead-letter-strategy","at-least-once","overflow","reject-publish")),201);
        broker.topology();
    }
    @AfterAll void cleanupBroker()throws Exception {
        try {broker.close();management("DELETE","vhosts/"+enc(),null,204);}
        finally {management("DELETE","users/"+user,null,204);}
    }
    @AfterEach void finishOwnRequests()throws Exception {
        broker.close();reset(async,outbox,lua,broker,orders,inventory,trades,catalog,clock,recoveryMapper);
        for(var f:fixtures) {
            db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE session_id=? AND state IN ('ACCEPTED','PROCESSING','RETRY_WAIT')",f.session());
            db.update("UPDATE tf_async_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE session_id=? AND state='PROCESSING'",f.session());
        }
        worker.sweep();publisher.tick();broker.close();fixtures.clear();
    }
    Fixture asyncFixture(int capacity,boolean open)throws Exception {
        Fixture f=new TransactionTemplate(manager).execute(status->{
            var now=trades.now();long e=catalog.createEvent("Async_"+key(),"Show","music","Beijing","Hall");
            long s=catalog.createSession(e,now.plusHours(2),now.plusMinutes(2),now.plusHours(1));
            long t=catalog.createTier(s,"Standard",58000,capacity),o=catalog.createTier(s,"VIP",88000,capacity);
            catalog.setStatus(e,"ON_SALE");return new Fixture(e,s,t,o);
        });fixtures.add(f);
        assertEquals("READY",data(mode(f,"ASYNC",0),200).path("phase").asString());
        if(open)open(f);return f;
    }
    void open(Fixture f) {
        db.update("UPDATE tf_session SET sale_start_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,freeze_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",f.session());
        redis.opsForHash().put(lua.keys(f.session(),gates.read(f.session()).epoch()).get(0),"start",Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
    }
    HttpResponse<String> mode(Fixture f,String mode,long version)throws Exception {
        return request("PUT","/api/v1/admin/sessions/"+f.session()+"/purchase-mode",Map.of("mode",mode,"expectedVersion",version),admin.token(),null);
    }
    HttpResponse<String> submit(Actor a,long tier,String key)throws Exception {
        return request("POST","/api/v1/purchase-requests",Map.of("tierId",Long.toString(tier),"quantity",1),a.token(),key);
    }
    String accepted(Actor a,Fixture f)throws Exception {return data(submit(a,f.tier(),key()),202).path("requestId").asString();}
    AsyncMessage message(String id) {
        String payload=db.queryForObject("SELECT payload FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER' ORDER BY created_at,id LIMIT 1",String.class,id);
        return json.readValue(payload,AsyncMessage.class);
    }
    void consume(String id) {assertEquals(AsyncDisposition.ACK,worker.consume(message(id)));}
    String state(String id) {return async.get(id,false).state();}
    long order(String id){return async.get(id,false).orderId();}
    long free(Fixture f){return Long.parseLong(redis.opsForHash().get(lua.keys(f.session(),gates.read(f.session()).epoch()).get(1),Long.toString(f.tier())).toString());}
    void balance(Fixture f,int queued,int reserved,int sold) {
        assertEquals(queued,count("SELECT queued_count FROM tf_async_tier_balance WHERE tier_id=?",f.tier()));
        assertEquals(reserved,count("SELECT reserved FROM tf_stock WHERE tier_id=?",f.tier()));
        assertEquals(sold,count("SELECT sold FROM tf_stock WHERE tier_id=?",f.tier()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_stock s JOIN tf_async_tier_balance b ON b.tier_id=s.tier_id JOIN tf_tier t ON t.id=s.tier_id WHERE t.session_id=? AND (s.capacity<>s.available+s.reserved+s.sold OR b.queued_count>s.available)",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_async_slot a JOIN tf_purchase_slot p ON p.user_id=a.user_id AND p.session_id=a.session_id WHERE a.session_id=?",f.session()));
    }
    void act(Actor a,long order,String operation,String key)throws Exception {data(request("POST","/api/v1/orders/"+order+"/"+operation,Map.of(),a.token(),key),200);}
    void flush(Fixture f)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do {
            publisher.tick();
            if(count("SELECT COUNT(*) FROM tf_outbox WHERE session_id=? AND destination='REDIS' AND state<>'SENT'",f.session())==0)return;
            Thread.sleep(100);
        }while(System.nanoTime()<end);
        fail("Redis projection backlog for own session");
    }
    AsyncToken orphan(Actor a,Fixture f,String key) {
        long epoch=gates.read(f.session()).epoch();
        var r=lua.reserve(f.session(),epoch,a.id(),key,TradeExecutor.hash("ASYNC:v1\ntierId="+f.tier()+"\nquantity=1"),f.tier(),key(),key(),clock.nowUtc().toInstant(ZoneOffset.UTC).toEpochMilli());
        assertEquals("RESERVED",r.code());
        return lua.tentative(f.session(),epoch,0,0).stream().filter(t->t.token().equals(r.token())).findFirst().orElseThrow();
    }
    void age(AsyncToken token,Fixture f) {
        var keys=lua.keys(f.session(),gates.read(f.session()).epoch());var node=(tools.jackson.databind.node.ObjectNode)json.readTree(redis.opsForHash().get(keys.get(2),token.token()).toString());
        long at=Instant.now().minusSeconds(11).toEpochMilli();node.put("at",at);
        redis.opsForHash().put(keys.get(2),token.token(),json.writeValueAsString(node));redis.opsForZSet().add(keys.get(5),token.token(),at);
    }
    void expireMaintenance(Fixture f) {db.update("UPDATE tf_async_gate SET updated_at=UTC_TIMESTAMP(6)-INTERVAL 31 SECOND WHERE session_id=?",f.session());}

    @Test void orphanSweepPersistsFenceBeforeReleaseAndLateOriginalKeyCannotBeAccepted()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();var t=orphan(a,f,k);age(t,f);
        recovery.sweepOrphans(f.session());recovery.sweepOrphans(f.session());
        assertEquals("REJECTED",state(t.id()));assertEquals(0,free(f));balance(f,0,0,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=?",t.id()));
        rejected(submit(a,f.tier(),k),"ADMISSION_EXPIRED");flush(f);assertEquals(1,free(f));
        assertEquals("OBSERVED_MATCH",reconciliation.inspect(f.session()).get("status"));
    }
    @Test void committedAdmissionWithLostResponseIsNotReleasedByOrphanSweep()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();String id=data(submit(a,f.tier(),k),202).path("requestId").asString();
        var t=lua.tentative(f.session(),gates.read(f.session()).epoch(),0,0).get(0);age(t,f);recovery.sweepOrphans(f.session());
        assertEquals("ACCEPTED",state(id));balance(f,1,0,0);assertEquals(0,free(f));
        assertEquals(id,data(submit(a,f.tier(),k),202).path("requestId").asString());consume(id);flush(f);
    }
    @Test void crossSessionOrphanSealingReleasesOnlyLosingToken()throws Exception {
        var f=asyncFixture(1,true);var g=asyncFixture(1,true);var a=actor(false);String k=key();var losing=orphan(a,f,k);
        String winner=data(submit(a,g.tier(),k),202).path("requestId").asString();age(losing,f);recovery.sweepOrphans(f.session());flush(f);
        assertEquals(1,free(f));assertEquals("ACCEPTED",state(winner));balance(g,1,0,0);consume(winner);
    }
    @Test void redisLossRebuildsQueuedAndPaidTokensAndOldEventsCannotDoubleRelease()throws Exception {
        var f=asyncFixture(3,true);var a=actor(false);String paid=accepted(a,f);consume(paid);act(a,order(paid),"payments",key());
        var b=actor(false);String pending=accepted(b,f);var oldMessage=message(pending);long old=gates.read(f.session()).epoch();
        redis.delete(lua.keys(f.session(),old));assertTrue(recovery.rebuild(f.session()));
        assertEquals(old+1,gates.read(f.session()).epoch());assertEquals(1,free(f));balance(f,1,0,1);
        assertEquals(AsyncDisposition.ACK,worker.consume(oldMessage));assertEquals("ACCEPTED",state(pending));
        String payload=db.queryForObject("SELECT payload FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER' AND epoch=?",String.class,pending,old+1);
        assertEquals(AsyncDisposition.ACK,worker.consume(json.readValue(payload,AsyncMessage.class)));flush(f);balance(f,0,1,1);
        act(a,order(paid),"refunds",key());act(b,order(pending),"cancel",key());flush(f);assertEquals(3,free(f));
        assertEquals("OBSERVED_MATCH",reconciliation.inspect(f.session()).get("status"));
    }
    @Test void rebuildSealsVisibleOrphansAndDoesNotExtendAcceptedDeadlines()throws Exception {
        var f=asyncFixture(2,true);String pending=accepted(actor(false),f);var deadline=async.get(pending,false).deadline();
        var t=orphan(actor(false),f,key());assertTrue(recovery.rebuild(f.session()));
        assertEquals("REJECTED",state(t.id()));assertEquals(deadline,async.get(pending,false).deadline());assertEquals(1,free(f));balance(f,1,0,0);
        db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",pending);worker.sweep();flush(f);assertEquals(2,free(f));
    }
    @Test void failedSnapshotWriteStaysClosedAndExpiredMaintenanceUsesFreshEpoch()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);long epoch=gates.read(f.session()).epoch();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test reply lost after Redis restore");}).when(lua).restore(any(AsyncSnapshot.class));
        assertThrows(IllegalStateException.class,()->recovery.rebuild(f.session()));reset(lua);
        assertEquals("REBUILDING",gates.read(f.session()).phase());assertFalse(recovery.rebuild(f.session()));assertEquals(1,count("SELECT COUNT(*) FROM tf_async_alert WHERE category='REBUILD' AND resource_id=? AND resolved_at IS NULL",Long.toString(f.session())));
        expireMaintenance(f);assertTrue(recovery.rebuild(f.session()));assertEquals(epoch+2,gates.read(f.session()).epoch());assertEquals(0,free(f));balance(f,1,0,0);
        assertEquals(0,count("SELECT COUNT(*) FROM tf_async_alert WHERE category='REBUILD' AND resource_id=? AND resolved_at IS NULL",Long.toString(f.session())));
        db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();flush(f);assertEquals(1,free(f));
    }
    @Test void snapshotMismatchCannotReopenGateOrInventFreeStock()throws Exception {
        var f=asyncFixture(2,true);String id=accepted(actor(false),f);db.update("UPDATE tf_async_tier_balance SET queued_count=0 WHERE tier_id=?",f.tier());
        try {assertEquals("DB_MISMATCH",reconciliation.inspect(f.session()).get("status"));assertThrows(IllegalStateException.class,()->recovery.rebuild(f.session()));assertEquals("PAUSED",gates.read(f.session()).phase());}
        finally {db.update("UPDATE tf_async_tier_balance SET queued_count=1 WHERE tier_id=?",f.tier());expireMaintenance(f);assertTrue(recovery.rebuild(f.session()));}
        balance(f,1,0,0);assertEquals(1,free(f));
    }
    @Test void projectionGapReplaysEvenPreviouslySentPredecessorBeforeRelease()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);consume(id);
        // Simulate the sender's SENT fact surviving a lost Redis projection.
        db.update("UPDATE tf_outbox SET state='SENT',sent_at=UTC_TIMESTAMP(6) WHERE aggregate_id=? AND event_type='ACTIVATE'",id);
        flush(f);assertEquals("ORDER",json.readTree(redis.opsForHash().get(lua.keys(f.session(),gates.read(f.session()).epoch()).get(2),async.get(id,false).token()).toString()).path("state").asString());
        act(a,order(id),"cancel",key());flush(f);assertEquals(1,free(f));
    }
    @Test void maintenanceOwnerChangeFencesStaleCoordinatorBeforeReady()throws Exception {
        var f=asyncFixture(1,true);orphan(actor(false),f,key());
        doAnswer(call->{call.callRealMethod();db.update("UPDATE tf_async_gate SET maintenance_version=maintenance_version+1,maintenance_owner='replacement' WHERE session_id=?",f.session());return null;}).when(lua).restore(any(AsyncSnapshot.class));
        assertThrows(IllegalStateException.class,()->recovery.rebuild(f.session()));reset(lua);assertEquals("REBUILDING",gates.read(f.session()).phase());
        expireMaintenance(f);assertTrue(recovery.rebuild(f.session()));assertEquals(1,free(f));
    }
    @Test void deadReplayIsDurableIdempotentAndKeepsOriginalDeadline()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);var m=message(id);var deadline=async.get(id,false).deadline();
        db.update("UPDATE tf_async_request SET attempt_count=5,state='RETRY_WAIT',next_retry_at=UTC_TIMESTAMP(6) WHERE id=?",id);
        assertTrue(deadReplay.replay(m));assertTrue(deadReplay.replay(m));assertEquals(1,count("SELECT COUNT(*) FROM tf_async_dead_replay WHERE event_id=?",m.eventId()));
        assertEquals(2,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER'",id));assertEquals(deadline,async.get(id,false).deadline());consume(id);balance(f,0,1,0);
        var malformed=new AsyncMessage(1,m.eventId(),m.requestId(),m.sessionId(),m.epoch(),m.createdAt(),"tampered");assertFalse(deadReplay.replay(malformed));
    }
    @Test void oneHundredInFlightConvergeWithinSixtySecondsIncludingRebuild()throws Exception {
        var f=asyncFixture(100,true);var actors=new ArrayList<Actor>();for(int i=0;i<100;i++)actors.add(actor(false));
        var pool=Executors.newFixedThreadPool(5);var ids=new ArrayList<String>();
        try {
            var pending=new ArrayList<Future<String>>();
            for(var a:actors)pending.add(pool.submit(()->accepted(a,f)));
            for(var future:pending)ids.add(future.get(60,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        balance(f,100,0,0);redis.delete(lua.keys(f.session(),gates.read(f.session()).epoch()));long start=System.nanoTime();
        assertTrue(recovery.rebuild(f.session()));broker.startConsumers();
        while(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(60)) {
            worker.sweep();publisher.tick();
            if(count("SELECT COUNT(*) FROM tf_async_request WHERE session_id=? AND state IN ('ACCEPTED','PROCESSING','RETRY_WAIT')",f.session())==0)break;
            Thread.sleep(100);
        }
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertEquals(0,count("SELECT COUNT(*) FROM tf_async_slot WHERE session_id=?",f.session()));
        assertEquals(100,count("SELECT COUNT(*) FROM tf_async_request WHERE session_id=? AND state IN ('SUCCEEDED','REJECTED')",f.session()));
        assertTrue(elapsed<60_000,"Recovery exceeded 60s: "+elapsed);flush(f);assertTrue(recoveryMapper.differences(f.session()).isEmpty());
        System.out.println("BATCH10_CONVERGENCE session="+f.session()+" requests=100 elapsedMs="+elapsed+" succeeded="+count("SELECT COUNT(*) FROM tf_async_request WHERE session_id=? AND state='SUCCEEDED'",f.session()));
    }
    Process child(String mode,java.nio.file.Path folder,Fixture f,Actor a,String id,String key)throws Exception {
        return child(mode,folder,f,a,id,key,3306);
    }
    Process child(String mode,java.nio.file.Path folder,Fixture f,Actor a,String id,String key,int databasePort)throws Exception {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var command=new ArrayList<String>(List.of(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,AsyncCrashWorker.class.getName(),mode,folder.toString(),id,Long.toString(f.session()),Long.toString(a.id()),Long.toString(f.tier()),key,
            "--server.port=0","--server.address=127.0.0.1","--ticketflow.async.enabled=true","--ticketflow.async.jobs-enabled=false","--ticketflow.expiry.enabled=false","--ticketflow.redis.enabled=false","--ticketflow.redis.namespace=tf:test:v1","--logging.level.root=WARN",
            "--ticketflow.jwt.private-key="+IdentityIntegrationIT.testKeys.resolve("private.pem"),"--ticketflow.jwt.public-key="+IdentityIntegrationIT.testKeys.resolve("public.pem")));
        if(databasePort!=3306)command.add("--spring.datasource.url=jdbc:mysql://127.0.0.1:"+databasePort+"/ticketflow_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(folder.resolve("worker.log").toFile());
        builder.environment().put("TF_RABBITMQ_USER",user);builder.environment().put("TF_RABBITMQ_PASSWORD",pass);builder.environment().put("TF_RABBITMQ_VHOST",vhost);builder.environment().put("TF_RABBITMQ_PREFIX",prefix);
        return builder.start();
    }
    void marker(Process process,java.nio.file.Path folder,String name)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(!java.nio.file.Files.exists(folder.resolve(name)) && process.isAlive() && System.nanoTime()<end)Thread.sleep(50);
        assertTrue(java.nio.file.Files.exists(folder.resolve(name)),()->"Missing "+name+" in "+folder+"; child alive="+process.isAlive());
    }
    void kill(Process process)throws Exception {process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void realCommitDisconnectUsesDurableOutcomeWithoutBlindCompensation(boolean afterCommit)throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();var folder=java.nio.file.Files.createTempDirectory("tf-async-commit-network-");
        try(var proxy=new AsyncMysqlCommitProxy(afterCommit)) {
            var process=child("admission-network",folder,f,a,key(),k,proxy.port());
            try {marker(process,folder,"ready");assertTrue(proxy.awaitFault(),"Real target COMMIT was not intercepted");}
            finally {if(process.isAlive())kill(process);}
        }
        var r=async.byKey(a.id(),k,false);var token=lua.tentative(f.session(),gates.read(f.session()).epoch(),0,0).get(0);
        if(afterCommit) {
            assertNotNull(r);assertEquals("ACCEPTED",r.state());assertEquals(r.id(),data(submit(a,f.tier(),k),202).path("requestId").asString());
            age(token,f);recovery.sweepOrphans(f.session());assertEquals(0,free(f));consume(r.id());balance(f,0,1,0);
        } else {
            assertNull(r);age(token,f);recovery.sweepOrphans(f.session());flush(f);assertEquals(1,free(f));rejected(submit(a,f.tier(),k),"ADMISSION_EXPIRED");balance(f,0,0,0);
        }
        System.out.println("BATCH10_DB_NETWORK afterCommit="+afterCommit+" folder="+folder);
    }

    @Test void actualDeadLetterReplaySurvivesLostAckWithoutSchedulingTwice()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);var m=message(id);
        db.update("UPDATE tf_async_request SET attempt_count=4 WHERE id=?",id);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test DLQ threshold")).when(async).complete(argThat(r->r.id().equals(id)),notNull(),eq("OK"),any());
        broker.startConsumers();assertTrue(broker.publish(outbox.get(m.eventId())));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(count("SELECT COUNT(*) FROM tf_async_alert WHERE category='DEAD_LETTER' AND resource_id=?",m.eventId())==0 && System.nanoTime()<end)Thread.sleep(100);
        assertEquals(5,async.get(id,false).attempts());broker.close();reset(async);
        boolean fault=false;end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!fault && System.nanoTime()<end) {
            try {broker.replayDead(m.eventId(),message->{assertTrue(deadReplay.replay(message));throw new IllegalStateException("test ACK lost after durable replay");});}
            catch(IllegalStateException expected){fault=true;}
            if(!fault)Thread.sleep(100);
        }
        assertTrue(fault);assertTrue(broker.replayDead(m.eventId(),deadReplay::replay));
        assertEquals(1,count("SELECT COUNT(*) FROM tf_async_dead_replay WHERE event_id=?",m.eventId()));
        assertEquals(2,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER'",id));consume(id);balance(f,0,1,0);
    }

    @Test void partialRedisTokenLossRetiresOldEpochWithoutReleasingCommittedRequest()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);long old=gates.read(f.session()).epoch();
        redis.delete(lua.keys(f.session(),old).get(2));assertTrue(recovery.rebuild(f.session()));
        assertEquals(old+1,gates.read(f.session()).epoch());assertEquals(0,free(f));balance(f,1,0,0);
        db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();flush(f);assertEquals(1,free(f));
    }
    @Test void orphanFenceWinsAgainstAnHttpRequestPausedAfterLua()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();var reserved=new CountDownLatch(1);var resume=new CountDownLatch(1);
        doAnswer(call->{Object result=call.callRealMethod();reserved.countDown();assertTrue(resume.await(5,TimeUnit.SECONDS));return result;})
            .when(lua).reserve(eq(f.session()),anyLong(),eq(a.id()),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->submit(a,f.tier(),k));assertTrue(reserved.await(5,TimeUnit.SECONDS));
            var token=lua.tentative(f.session(),gates.read(f.session()).epoch(),0,0).get(0);age(token,f);recovery.sweepOrphans(f.session());resume.countDown();
            rejected(pending.get(5,TimeUnit.SECONDS),"ADMISSION_EXPIRED");assertEquals("REJECTED",state(token.id()));balance(f,0,0,0);flush(f);assertEquals(1,free(f));
        }finally{resume.countDown();pool.shutdownNow();reset(lua);}
    }
    @Test void admissionCommitWinsAgainstOrphanSealingWaitingForOwnerLock()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();var inserted=new CountDownLatch(1);var resume=new CountDownLatch(1);
        doAnswer(call->{call.callRealMethod();inserted.countDown();assertTrue(resume.await(4,TimeUnit.SECONDS));return null;})
            .when(async).slot(argThat(r->r.userId()==a.id()));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var pending=pool.submit(()->submit(a,f.tier(),k));assertTrue(inserted.await(5,TimeUnit.SECONDS));
            var token=lua.tentative(f.session(),gates.read(f.session()).epoch(),0,0).get(0);age(token,f);
            var sweep=pool.submit(()->recovery.sweepOrphans(f.session()));Thread.sleep(100);assertFalse(sweep.isDone());resume.countDown();
            String id=data(pending.get(5,TimeUnit.SECONDS),202).path("requestId").asString();sweep.get(5,TimeUnit.SECONDS);
            assertEquals("ACCEPTED",state(id));balance(f,1,0,0);assertEquals(0,free(f));consume(id);
        }finally{resume.countDown();pool.shutdownNow();reset(async);}
    }
    @Test void scheduledRecoveryDetectsLostViewAndRebuildsBeforeReopening()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);long old=gates.read(f.session()).epoch();redis.delete(lua.keys(f.session(),old));
        doReturn(List.of(f.session())).when(recoveryMapper).sessions(anyLong());recovery.tick();reset(recoveryMapper);
        assertEquals("READY",gates.read(f.session()).phase());assertEquals(old+1,gates.read(f.session()).epoch());assertEquals(0,free(f));balance(f,1,0,0);
        db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();flush(f);assertEquals(1,free(f));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"admission-before","admission-after"})
    void killedAdmissionResolvesFromDurableFacts(String mode)throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();var folder=java.nio.file.Files.createTempDirectory("tf-async-"+mode+"-");
        var process=child(mode,folder,f,a,key(),k);
        try {marker(process,folder,"window");kill(process);}finally{if(process.isAlive())kill(process);}
        var persisted=async.byKey(a.id(),k,false);var token=lua.tentative(f.session(),gates.read(f.session()).epoch(),0,0).get(0);
        if(mode.equals("admission-before")) {
            assertNull(persisted);age(token,f);recovery.sweepOrphans(f.session());rejected(submit(a,f.tier(),k),"ADMISSION_EXPIRED");flush(f);assertEquals(1,free(f));balance(f,0,0,0);
        } else {
            assertNotNull(persisted);assertEquals(persisted.id(),data(submit(a,f.tier(),k),202).path("requestId").asString());
            age(token,f);recovery.sweepOrphans(f.session());assertEquals(0,free(f));consume(persisted.id());balance(f,0,1,0);
        }
        System.out.println("BATCH10_KILL window="+mode+" folder="+folder);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"order-before","order-after"})
    void killedConsumerRedeliveryHasOnlyOneCommittedOrder(String mode)throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);var folder=java.nio.file.Files.createTempDirectory("tf-async-"+mode+"-");
        var process=child(mode,folder,f,a,id,key());
        try {
            marker(process,folder,"ready");
            // Fixture window begins after subprocess startup; this is a crash test, not a deadline timing test.
            db.update("UPDATE tf_async_request SET create_deadline=UTC_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE id=?",id);
            assertTrue(broker.publish(outbox.get(message(id).eventId())));marker(process,folder,"window");kill(process);
        }finally{if(process.isAlive())kill(process);}
        assertEquals(mode.equals("order-after")?1:0,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));
        db.update("UPDATE tf_async_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=? AND state='PROCESSING'",id);
        broker.startConsumers();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!"SUCCEEDED".equals(state(id)) && System.nanoTime()<end){publisher.tick();Thread.sleep(100);}
        assertEquals("SUCCEEDED",state(id));consume(id);balance(f,0,1,0);assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));flush(f);
        System.out.println("BATCH10_KILL window="+mode+" folder="+folder);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"rebuild-paused","rebuild-frozen","rebuild-snapshot","rebuild-restored","rebuild-db-ready","rebuild-ready"})
    void killedRebuildAtEveryPublishedBoundaryCanResume(String mode)throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);var deadline=async.get(id,false).deadline();
        var folder=java.nio.file.Files.createTempDirectory("tf-async-"+mode+"-");var process=child(mode,folder,f,a,id,key());
        try {marker(process,folder,"window");kill(process);}finally{if(process.isAlive())kill(process);}
        expireMaintenance(f);assertTrue(recovery.rebuild(f.session()));assertEquals(deadline,async.get(id,false).deadline());
        db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();flush(f);
        assertEquals("REJECTED",state(id));assertEquals(1,free(f));balance(f,0,0,0);assertTrue(recoveryMapper.differences(f.session()).isEmpty());
        System.out.println("BATCH10_KILL window="+mode+" folder="+folder);
    }
    @Test void reliable202ContainsFourDurableFactsBeforeBrokerPublication()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);var response=submit(a,f.tier(),key());String id=data(response,202).path("requestId").asString();
        assertEquals("1",response.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("/api/v1/purchase-requests/"+id,response.headers().firstValue("Location").orElseThrow());
        assertEquals(1,count("SELECT COUNT(*) FROM tf_async_slot WHERE request_id=?",id));
        assertEquals(2,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND state='PENDING'",id));
        assertEquals("ACCEPTED",state(id));assertEquals(0,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));balance(f,1,0,0);assertEquals(0,free(f));
        consume(id);balance(f,0,1,0);flush(f);
    }
    @Test void actualRabbitDeliveryCreatesOneOrderAndConfirmMarksOutboxSent()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);
        broker.startConsumers();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!"SUCCEEDED".equals(state(id))&&System.nanoTime()<end){publisher.tick();Thread.sleep(100);}
        assertEquals("SUCCEEDED",state(id));balance(f,0,1,0);flush(f);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));
        assertEquals(1,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER' AND state='SENT'",id));
        var own=data(request("GET","/api/v1/purchase-requests/"+id,null,a.token(),null),200);
        assertEquals("PENDING",own.path("currentOrderStatus").asString());assertEquals(58000,own.path("snapshot").path("amountFen").asLong());
    }
    @Test void sameKeyReplaysQueuedAndTerminalResultAndChangedParametersConflict()throws Exception {
        var f=asyncFixture(2,true);var a=actor(false);String k=key();String id=data(submit(a,f.tier(),k),202).path("requestId").asString();
        var replay=submit(a,f.tier(),k);assertEquals(id,data(replay,202).path("requestId").asString());assertTrue(body(replay).path("replayed").asBoolean());
        rejected(submit(a,f.otherTier(),k),"IDEMPOTENCY_CONFLICT");consume(id);
        assertEquals(id,data(submit(a,f.tier(),k),200).path("requestId").asString());balance(f,0,1,0);
    }
    @Test void concurrentIdenticalRequestsAndDuplicateMessagesDoNotCreateTwice()throws Exception {
        var f=asyncFixture(2,true);var a=actor(false);String k=key();
        var results=simultaneous(List.of(()->submit(a,f.tier(),k),()->submit(a,f.tier(),k)));
        String id=data(results.get(0),202).path("requestId").asString();assertEquals(id,data(results.get(1),202).path("requestId").asString());
        var pool=Executors.newFixedThreadPool(2);
        try {var m=message(id);var x=pool.submit(()->worker.consume(m));var y=pool.submit(()->worker.consume(m));assertEquals(AsyncDisposition.ACK,x.get(10,TimeUnit.SECONDS));assertEquals(AsyncDisposition.ACK,y.get(10,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
        consume(id);assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));balance(f,0,1,0);
    }
    @Test void concurrentDifferentTiersShareTheSameSessionQualification()throws Exception {
        var f=asyncFixture(2,true);var a=actor(false);
        var responses=simultaneous(List.of(()->submit(a,f.tier(),key()),()->submit(a,f.otherTier(),key())));
        assertEquals(List.of(202,409),responses.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1,count("SELECT COUNT(*) FROM tf_async_slot WHERE user_id=? AND session_id=?",a.id(),f.session()));
        responses.stream().filter(r->r.statusCode()==202).forEach(r->consume(body(r).path("data").path("requestId").asString()));
        assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));
    }
    @Test void sixBuyersCompeteForTheLastTicketWithoutOverselling()throws Exception {
        var f=asyncFixture(1,true);var tasks=new ArrayList<Callable<HttpResponse<String>>>();
        for(int i=0;i<6;i++){var a=actor(false);tasks.add(()->submit(a,f.tier(),key()));}
        var responses=simultaneous(tasks);assertEquals(1,responses.stream().filter(r->r.statusCode()==202).count());assertEquals(5,responses.stream().filter(r->r.statusCode()==409).count());
        balance(f,1,0,0);responses.stream().filter(r->r.statusCode()==202).forEach(r->consume(body(r).path("data").path("requestId").asString()));balance(f,0,1,0);assertEquals(0,free(f));
    }
    @Test void crossSessionSameKeyReleasesOnlyTheLosingCandidate()throws Exception {
        var f=asyncFixture(1,true);var g=asyncFixture(1,true);var a=actor(false);String k=key();var barrier=new CyclicBarrier(2);
        doAnswer(call->{Object result=call.callRealMethod();barrier.await(5,TimeUnit.SECONDS);return result;}).when(lua).reserve(anyLong(),anyLong(),eq(a.id()),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong());
        var responses=simultaneous(List.of(()->submit(a,f.tier(),k),()->submit(a,g.tier(),k)));reset(lua);
        assertEquals(List.of(202,409),responses.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1,count("SELECT COUNT(*) FROM tf_async_request WHERE user_id=? AND request_key=?",a.id(),k));
        flush(f);flush(g);assertEquals(1,free(f)+free(g));
        var winner=responses.stream().filter(r->r.statusCode()==202).findFirst().orElseThrow();String id=body(winner).path("data").path("requestId").asString();consume(id);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE user_id=? AND session_id IN (?,?)",a.id(),f.session(),g.session()));
    }
    @Test void requestOwnershipValidationAndModeRulesAreEnforced()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);var stranger=actor(false);String id=accepted(a,f);
        assertEquals(404,request("GET","/api/v1/purchase-requests/"+id,null,stranger.token(),null).statusCode());
        assertEquals(401,request("GET","/api/v1/purchase-requests/"+id,null,null,null).statusCode());
        assertEquals(400,submit(a,f.tier(),"short").statusCode());
        assertEquals(400,request("POST","/api/v1/purchase-requests",Map.of("tierId",Long.toString(f.tier()),"quantity",2),a.token(),key()).statusCode());
        assertEquals(403,request("PUT","/api/v1/admin/sessions/"+f.session()+"/purchase-mode",Map.of("mode","SYNC","expectedVersion",1),a.token(),null).statusCode());
        rejected(create(stranger,f.tier(),key()),"ASYNC_REQUIRED");rejected(mode(f,"SYNC",1),"CONFIG_FROZEN");
        assertEquals("READY",redis.opsForHash().get(lua.keys(f.session(),gates.read(f.session()).epoch()).get(0),"phase"));consume(id);
    }
    @Test void syncSessionRetainsOriginalPathAndAsyncModeUsesOptimisticVersion()throws Exception {
        var a=actor(false);var syncFixture=fixture(1);rejected(submit(a,syncFixture.tier(),key()),"SYNC_REQUIRED");data(create(a,syncFixture.tier(),key()),201);
        var f=asyncFixture(1,false);rejected(mode(f,"SYNC",0),"VERSION_CONFLICT");
        assertEquals("READY",redis.opsForHash().get(lua.keys(f.session(),gates.read(f.session()).epoch()).get(0),"phase"));
        assertEquals("SYNC",data(mode(f,"SYNC",1),200).path("mode").asString());
    }
    @Test void initialSoldOutFailureRemainsImmutableAfterCancellation()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);var b=actor(false);String id=accepted(a,f);consume(id);String k=key();
        var failure=submit(b,f.tier(),k);rejected(failure,"SOLD_OUT");String failed=body(failure).path("data").path("requestId").asString();
        assertNull(async.get(failed,false).token());act(a,order(id),"cancel",key());flush(f);assertEquals(1,free(f));
        var replay=submit(b,f.tier(),k);rejected(replay,"SOLD_OUT");assertTrue(body(replay).path("replayed").asBoolean());
        String second=accepted(b,f);consume(second);balance(f,0,1,0);
    }
    @Test void cancellationAndRefundReturnInventoryOnceAndHistoricalSuccessRemains()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);consume(id);long order=order(id);String cancel=key();
        act(a,order,"cancel",cancel);act(a,order,"cancel",cancel);flush(f);balance(f,0,0,0);assertEquals(1,free(f));
        var result=data(request("GET","/api/v1/purchase-requests/"+id,null,a.token(),null),200);assertEquals("SUCCEEDED",result.path("state").asString());assertEquals("CANCELLED",result.path("currentOrderStatus").asString());
        String second=accepted(a,f);consume(second);long paid=order(second);act(a,paid,"payments",key());balance(f,0,0,1);String refund=key();
        act(a,paid,"refunds",refund);act(a,paid,"refunds",refund);flush(f);balance(f,0,0,0);assertEquals(1,free(f));assertEquals("SUCCEEDED",state(second));
        String third=accepted(a,f);consume(third);balance(f,0,1,0);
    }
    @Test void disabledOwnerAndDeadlineScannerReleaseDurableQueuedBalance()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",a.id());consume(id);
        assertEquals("ACCOUNT_DISABLED",async.get(id,false).code());balance(f,0,0,0);flush(f);assertEquals(1,free(f));
        var b=actor(false);String pending=accepted(b,f);db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",b.id());
        db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",pending);worker.sweep();worker.sweep();
        assertEquals("REJECTED",state(pending));balance(f,0,0,0);flush(f);assertEquals(1,free(f));
    }
    @Test void offSaleBetweenAdmissionAndConsumptionRejectsAndReleases()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);catalog.setStatus(f.event(),"OFF_SALE");consume(id);
        assertEquals("NOT_ON_SALE",async.get(id,false).code());balance(f,0,0,0);flush(f);assertEquals(1,free(f));
    }
    @Test void consumerChecksBusinessDeadlineAfterWaitingForStockLock()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);
        db.update("UPDATE tf_async_request SET create_deadline=UTC_TIMESTAMP(6)+INTERVAL 700000 MICROSECOND WHERE id=?",id);
        var pool=Executors.newSingleThreadExecutor();
        try(var c=source.getConnection()) {
            c.setAutoCommit(false);try(var s=c.prepareStatement("SELECT tier_id FROM tf_stock WHERE tier_id=? FOR UPDATE")){s.setLong(1,f.tier());s.executeQuery().close();}
            var task=pool.submit(()->worker.consume(message(id)));Thread.sleep(1100);c.commit();assertEquals(AsyncDisposition.ACK,task.get(8,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertEquals("PROCESSING_DEADLINE_EXCEEDED",async.get(id,false).code());balance(f,0,0,0);flush(f);
    }
    @Test void admissionRechecksSaleTimeAfterLuaReservation()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);
        doAnswer(call->{var r=call.callRealMethod();db.update("UPDATE tf_session SET sale_end_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",f.session());return r;})
                .when(lua).reserve(eq(f.session()),anyLong(),eq(a.id()),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong());
        rejected(submit(a,f.tier(),key()),"SALE_ENDED");reset(lua);balance(f,0,0,0);flush(f);assertEquals(1,free(f));
    }
    @Test void admissionTransactionFailureRollsBackAllFourDurableFactsAndSameKeyRetries()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test only"))
                .when(outbox).insert(anyString(),eq("BROKER"),anyString(),anyString(),eq(f.session()),anyLong(),anyLong(),nullable(Long.class),anyString(),any(LocalDateTime.class));
        var response=submit(a,f.tier(),k);assertEquals(503,response.statusCode());assertTrue(body(response).path("data").path("retryWithSameKey").asBoolean());
        assertEquals(0,count("SELECT COUNT(*) FROM tf_async_request WHERE user_id=? AND request_key=?",a.id(),k));assertEquals(0,count("SELECT COUNT(*) FROM tf_async_slot WHERE session_id=?",f.session()));
        assertEquals(0,count("SELECT COUNT(*) FROM tf_outbox WHERE session_id=?",f.session()));balance(f,0,0,0);assertEquals(0,free(f));
        reset(outbox);String id=data(submit(a,f.tier(),k),202).path("requestId").asString();consume(id);balance(f,0,1,0);
    }
    @Test void consumerTransactionFailureCommitsRetryIntentBeforeAcknowledgement()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test only")).when(async).complete(any(AsyncRequest.class),notNull(),eq("OK"),any(LocalDateTime.class));
        assertEquals(AsyncDisposition.ACK,worker.consume(message(id)));assertEquals("RETRY_WAIT",state(id));balance(f,1,0,0);
        assertEquals(0,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));assertEquals(2,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER'",id));
        reset(async);db.update("UPDATE tf_async_request SET next_retry_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",id);consume(id);balance(f,0,1,0);
    }
    @Test void failedRecoveryWriteStopsConsumerAndKeepsDurableClaimForScanner()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test only")).when(async).complete(any(AsyncRequest.class),notNull(),eq("OK"),any(LocalDateTime.class));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test only")).when(async).retry(any(AsyncRequest.class),any(LocalDateTime.class),any(LocalDateTime.class));
        assertEquals(AsyncDisposition.STOP,worker.consume(message(id)));assertEquals("PROCESSING",state(id));balance(f,1,0,0);
        reset(async);db.update("UPDATE tf_async_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();assertEquals("RETRY_WAIT",state(id));
        db.update("UPDATE tf_async_request SET next_retry_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",id);consume(id);balance(f,0,1,0);
    }
    @Test void brokerUnavailableLeavesOutboxPendingAndCanLaterPublish()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);
        doThrow(new java.io.IOException("test broker connection unavailable")).when(broker).topology();
        doThrow(new java.io.IOException("test unavailable")).when(broker).publish(argThat(e->e.aggregate().equals(id)));
        long dispatchEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do {new AsyncJob(broker,publisher,worker,recovery).dispatch();}
        while(count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='REDIS' AND state='SENT'",id)==0 && System.nanoTime()<dispatchEnd);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='REDIS' AND state='SENT'",id));
        assertEquals(1,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER' AND state='PENDING'",id));balance(f,1,0,0);
        reset(broker);db.update("UPDATE tf_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE aggregate_id=? AND destination='BROKER'",id);publisher.tick();
        assertEquals(1,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND destination='BROKER' AND state='SENT'",id));consume(id);balance(f,0,1,0);
    }
    @Test void sentMarkerFailureAllowsRetransmissionWithoutAnotherOrder()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);String event=message(id).eventId();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test after confirm")).when(outbox).settle(argThat(e->e.id().equals(event)),anyString(),eq(true),any(LocalDateTime.class));
        assertThrows(org.springframework.dao.DataAccessException.class,publisher::tick);assertEquals("SENDING",db.queryForObject("SELECT state FROM tf_outbox WHERE id=?",String.class,event));
        reset(outbox);consume(id);db.update("UPDATE tf_outbox SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",event);publisher.tick();consume(id);balance(f,0,1,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));
    }
    @Test void projectionDuplicateGapAndWrongRedisTypeDoNotDoubleRelease()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);consume(id);flush(f);act(a,order(id),"cancel",key());flush(f);
        String eid=db.queryForObject("SELECT id FROM tf_outbox WHERE aggregate_id=? AND event_type='RELEASE'",String.class,id);var e=outbox.get(eid);var p=json.readTree(e.payload());
        assertEquals("DUPLICATE",lua.project(e,p.path("token").asString(),a.id(),id,null));assertEquals(1,free(f));
        var gap=new OutboxEvent(key(),"REDIS","RELEASE",id,f.session(),f.tier(),e.epoch(),e.sequence()+2,e.payload(),0,0);
        assertEquals("GAP",lua.project(gap,p.path("token").asString(),a.id(),id,null));assertEquals(1,free(f));
        String tokens=lua.keys(f.session(),e.epoch()).get(2);var saved=redis.opsForHash().entries(tokens);redis.delete(tokens);redis.opsForValue().set(tokens,"wrong type");
        try{var b=actor(false);assertEquals(503,submit(b,f.tier(),key()).statusCode());assertEquals(1,free(f));assertEquals(0,count("SELECT COUNT(*) FROM tf_async_request WHERE user_id=?",b.id()));}
        finally{redis.delete(tokens);redis.opsForHash().putAll(tokens,saved);}
    }
    @Test void malformedEnvelopeCannotCreateOrderOrInventARelease()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);var real=message(id);
        var unknown=new AsyncMessage(1,key(),id,f.session(),real.epoch(),real.createdAt(),"test");
        assertEquals(AsyncDisposition.DEAD_LETTER,worker.consume(unknown));balance(f,1,0,0);assertEquals(2,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=?",id));consume(id);
    }
    @Test void queryQuotaIsIndependentAndExistingResultNeedsNoRedis()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();String id=data(submit(a,f.tier(),k),202).path("requestId").asString();
        doThrow(new IllegalStateException("test disconnected")).when(lua).reserve(anyLong(),anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong());
        while(System.nanoTime()%1_000_000_000L>100_000_000L)Thread.sleep(5);
        purchases.get(a.id(),id);purchases.get(a.id(),id);
        assertThrows(com.ticketflow.common.exception.RateLimitedException.class,()->purchases.get(a.id(),id));
        assertEquals(id,data(submit(a,f.tier(),k),202).path("requestId").asString());reset(lua);consume(id);
    }
    @Test void expiredWorkerLeaseRetriesWithoutReleasingBeforeBusinessDeadline()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{LocalDateTime now=(LocalDateTime)call.callRealMethod();return calls.incrementAndGet()==2?now.plusSeconds(9):now;}).when(clock).nowUtc();
        consume(id);reset(clock);assertEquals("RETRY_WAIT",state(id));balance(f,1,0,0);
        assertEquals(0,count("SELECT COUNT(*) FROM tf_outbox WHERE aggregate_id=? AND event_type='RELEASE'",id));
        db.update("UPDATE tf_async_request SET next_retry_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",id);consume(id);balance(f,0,1,0);
    }
    @Test void staleWorkVersionCannotCommitAnOrder()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{var row=call.callRealMethod();if(calls.incrementAndGet()==2)db.update("UPDATE tf_async_request SET work_version=work_version+1,lease_owner='test-new-worker' WHERE id=?",id);return row;})
                .when(async).get(eq(id),eq(true));
        consume(id);reset(async);assertEquals("PROCESSING",state(id));balance(f,1,0,0);assertEquals(0,count("SELECT COUNT(*) FROM tf_order WHERE session_id=?",f.session()));
        db.update("UPDATE tf_async_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();
        db.update("UPDATE tf_async_request SET next_retry_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",id);consume(id);balance(f,0,1,0);
    }
    @Test void retryThresholdDeadLettersButDeadlineScanStillConverges()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);db.update("UPDATE tf_async_request SET attempt_count=4 WHERE id=?",id);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test fifth failure")).when(async).complete(any(AsyncRequest.class),notNull(),eq("OK"),any(LocalDateTime.class));
        assertEquals(AsyncDisposition.DEAD_LETTER,worker.consume(message(id)));assertEquals(5,async.get(id,false).attempts());assertEquals("RETRY_WAIT",state(id));balance(f,1,0,0);
        reset(async);db.update("UPDATE tf_async_request SET create_deadline=accepted_at+INTERVAL 1 MICROSECOND WHERE id=?",id);worker.sweep();assertEquals("REJECTED",state(id));balance(f,0,0,0);flush(f);
    }
    @Test void preSaleCapacityChangePausesViewAndExplicitRefreshUsesNewEpoch()throws Exception {
        var f=asyncFixture(1,false);long epoch=gates.read(f.session()).epoch();
        data(request("PUT","/api/v1/admin/tiers/"+f.tier(),Map.of("name","Standard","priceFen",58000,"capacity",2,"expectedVersion",0),admin.token(),null),200);
        assertEquals("PAUSED",gates.read(f.session()).phase());assertTrue(gates.read(f.session()).epoch()>epoch);
        assertEquals(503,submit(actor(false),f.tier(),key()).statusCode());
        data(mode(f,"ASYNC",1),200);assertEquals(2,free(f));open(f);String id=accepted(actor(false),f);consume(id);balance(f,0,1,0);
    }
    @Test void asyncExpiryCloseReleasesOnceEvenIfOwnerIsDisabled()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String id=accepted(a,f);consume(id);long order=order(id);
        db.update("UPDATE tf_order SET expires_at=created_at+INTERVAL 1 MICROSECOND WHERE id=?",order);db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?",a.id());
        assertTrue(lifecycle.closeExpired(order));assertFalse(lifecycle.closeExpired(order));flush(f);balance(f,0,0,0);assertEquals(1,free(f));assertEquals("SUCCEEDED",state(id));
    }
    @Test void actualMalformedRabbitMessageGoesToDurableDeadLetterQueue()throws Exception {
        var f=asyncFixture(1,true);String id=accepted(actor(false),f);broker.startConsumers();
        var factory=new ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(15673);factory.setVirtualHost(vhost);factory.setUsername(user);factory.setPassword(pass);
        try(var c=factory.newConnection();var ch=c.createChannel()) {
            ch.confirmSelect();ch.basicPublish(broker.exchange(),"create",true,new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).messageId(key()).build(),"{\"schemaVersion\":999}".getBytes(StandardCharsets.UTF_8));assertTrue(ch.waitForConfirms(2000));
            GetResponse dead=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(dead==null&&System.nanoTime()<end){dead=ch.basicGet(broker.deadQueue(),true);if(dead==null)Thread.sleep(100);}
            assertNotNull(dead);assertEquals(2,dead.getProps().getDeliveryMode());balance(f,1,0,0);assertEquals("ACCEPTED",state(id));consume(id);
        }
    }
    @Test void newAdmissionQuotaDoesNotPersist429OrBlockOriginalKeyRecovery()throws Exception {
        var f=asyncFixture(1,true);var a=actor(false);String k=key();String id=data(submit(a,f.tier(),k),202).path("requestId").asString();
        String rate=lua.keys(f.session(),gates.read(f.session()).epoch()).get(7);
        redis.opsForHash().putAll(rate,Map.of("window",Long.toString(Instant.now().toEpochMilli()/60_000),"u:"+a.id(),"1000","s","1000"));
        // Replaying the durable key never visits Lua, regardless of new-request quota.
        assertEquals(id,data(submit(a,f.tier(),k),202).path("requestId").asString());
        while(Instant.now().toEpochMilli()%60_000>58_500)Thread.sleep(50);
        redis.opsForHash().putAll(rate,Map.of("window",Long.toString(Instant.now().toEpochMilli()/60_000),"u:"+a.id(),"1000","s","1000"));
        String next=key();var response=submit(a,f.tier(),next);
        if(response.statusCode()==429){assertEquals("1",response.headers().firstValue("Retry-After").orElseThrow());assertEquals(0,count("SELECT COUNT(*) FROM tf_async_request WHERE user_id=? AND request_key=?",a.id(),next));}
        else fail("Expected quota rejection in seeded current Redis window");
        redis.delete(rate);consume(id);
    }
}
