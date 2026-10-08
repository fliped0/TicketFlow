package com.ticketflow;

import com.rabbitmq.client.*;
import com.ticketflow.config.*;
import com.ticketflow.mapper.MqLabMapper;
import com.ticketflow.model.*;
import com.ticketflow.service.MqLabService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated transport/transaction lab; no order endpoint or production outbox is implemented here. */
@EnabledIfEnvironmentVariable(named="TF_RABBITMQ_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RabbitMqLabIT extends OrderTestSupport {
    private final String id=UUID.randomUUID().toString().replace("-","").substring(0,12);
    private final String prefix="b8_"+id+"_", vhost="/ticketflow-lab-"+id, user="tf_lab_"+id;
    private final String password=UUID.randomUUID().toString()+UUID.randomUUID();
    private final int retryMillis=Integer.parseInt(System.getenv().getOrDefault("TF_MQ_LAB_RETRY_MS","400"));
    private final List<Map<String,Object>> observations=new ArrayList<>();
    private MqLabMapper lab; private MqLabService service; private MqLabBroker broker;
    private Connection connection; private Channel channel; private MqLabTopology topology; private String account;
    private boolean vhostCreated,userCreated;
    private final Path report=Path.of("target/mq-lab-observations.json");

    private JsonNode admin(String method,String resource,Object payload,int expected) throws Exception {
        String basic=Base64.getEncoder().encodeToString(("tf_admin:"+System.getenv("TF_RABBITMQ_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:15672/api/"+resource))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10)).header("Authorization","Basic "+basic).header("Content-Type","application/json")
                .method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if("DELETE".equals(method) && expected==204 && response.statusCode()==404) return json.createObjectNode();
        assertEquals(expected,response.statusCode(),"RabbitMQ management operation: "+method+" "+resource);
        return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
    private String encodedVhost() { return URLEncoder.encode(vhost,StandardCharsets.UTF_8); }
    @BeforeAll void createLab() throws Exception {
        assertTrue(retryMillis>=100 && retryMillis<=2000);
        try {
            vhostCreated=true;admin("PUT","vhosts/"+encodedVhost(),Map.of(),201);
            userCreated=true;admin("PUT","users/"+user,Map.of("password",password,"tags",""),201);
            admin("PUT","permissions/"+encodedVhost()+"/"+user,Map.of("configure","^lab\\..*","write","^lab\\..*","read","^lab\\..*"),201);
            admin("PUT","policies/"+encodedVhost()+"/lab-dlx",Map.of("pattern","^lab\\.","priority",1,"apply-to","queues",
                    "definition",Map.of("dead-letter-strategy","at-least-once","overflow","reject-publish")),201);
            var scoped=admin("GET","users/"+user+"/permissions",null,200);
            assertEquals(1,scoped.size());assertEquals(vhost,scoped.get(0).path("vhost").asString());
            lab=new MqLabMapper(db,prefix);lab.createTables();service=new MqLabService(lab,manager);
            broker=new MqLabBroker(user,password,vhost);
        } catch(Exception | AssertionError failure) {
            try { cleanupBroker(); } catch(Exception | AssertionError cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }
    @BeforeEach void prepareLab() throws Exception {
        account=key();lab.account(account);connection=broker.connect();channel=connection.createChannel();channel.basicQos(1);
        topology=MqLabTopology.declare(channel,"lab."+key().replace("-","").substring(0,12),retryMillis);
    }
    @AfterEach void closeConsumer() throws Exception {
        if(channel!=null && channel.isOpen()) channel.close();
        if(connection!=null && connection.isOpen()) connection.close();
    }
    private void cleanupBroker() throws Exception {
        try { if(vhostCreated) { admin("DELETE","vhosts/"+encodedVhost(),null,204);vhostCreated=false; } }
        finally { if(userCreated) { admin("DELETE","users/"+user,null,204);userCreated=false; } }
    }
    @AfterAll void recordAndCleanup() throws Exception {
        try { cleanupBroker(); }
        finally {
            Files.createDirectories(report.getParent());
            Files.writeString(report,json.writeValueAsString(Map.of("rabbitmqVersion","4.3.6","dbTablePrefix",prefix,
                    "vhostDeleted",!vhostCreated,"scopedUserDeleted",!userCreated,"retryBaseMillis",retryMillis,
                    "cases",observations,"scope","synthetic account/inbox/ledger; not ticket orders")));
        }
    }
    private MqLabMessage message() { return MqLabMessage.create(account); }
    private void publish(MqLabMessage message) throws Exception {
        assertEquals(MqLabPublishOutcome.CONFIRMED,MqLabBroker.publish(channel,topology.exchange(),"work",message));
    }
    private GetResponse delivery() throws Exception { return MqLabBroker.receive(channel,topology.work()); }
    private boolean applyAck(GetResponse delivery) throws Exception {
        boolean changed=service.apply(MqLabBroker.decode(delivery));MqLabBroker.ack(channel,topology.work(),delivery);return changed;
    }
    private void counts(int expected) {
        assertEquals(expected,lab.effects(account));assertEquals(expected,lab.inbox(account));assertEquals(expected,lab.ledger(account));
    }
    private void observed(String name,Object detail) {
        observations.add(Map.of("case",name,"detail",detail,"effects",lab.effects(account),"inbox",lab.inbox(account),"ledger",lab.ledger(account)));
    }

    @Test @Order(1) void confirmedPersistentMessageAndCommitBeforeAck() throws Exception {
        var message=message();publish(message);var delivery=delivery();
        assertEquals(2,delivery.getProps().getDeliveryMode());assertEquals(message.eventId(),delivery.getProps().getMessageId());
        assertTrue(applyAck(delivery));counts(1);assertNull(channel.basicGet(topology.work(),false));
        var queue=admin("GET","queues/"+encodedVhost()+"/"+topology.work(),null,200);
        // Management collection is asynchronous; wait for its sample, keeping the exact policy assertion.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!"at-least-once".equals(queue.path("effective_policy_definition").path("dead-letter-strategy").asString())
                && System.nanoTime()<deadline) {
            Thread.sleep(100);queue=admin("GET","queues/"+encodedVhost()+"/"+topology.work(),null,200);
        }
        assertEquals("quorum",queue.path("type").asString());
        assertEquals("at-least-once",queue.path("effective_policy_definition").path("dead-letter-strategy").asString());
        observed("confirm/persistence/manual ACK",Map.of("deliveryMode",2,"queueType","quorum","effectCommittedBeforeAck",true));
    }
    @Test @Order(2) void mandatoryReturnMustNotBeMistakenForPublishSuccess() throws Exception {
        assertEquals(MqLabPublishOutcome.RETURNED,MqLabBroker.publish(channel,topology.exchange(),"missing",message()));
        counts(0);assertNull(channel.basicGet(topology.work(),false));observed("mandatory return",Map.of("publishOutcome","RETURNED"));
    }
    @Test @Order(3) void brokerExplicitNackOnBoundedQueueOverflow() throws Exception {
        String queue=topology.work()+".overflow";
        channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum","x-max-length",1,"x-overflow","reject-publish"));
        channel.queueBind(queue,topology.exchange(),"overflow");
        MqLabPublishOutcome result=MqLabPublishOutcome.CONFIRMED;int attempts=0;
        while(result==MqLabPublishOutcome.CONFIRMED && attempts++<20) result=MqLabBroker.publish(channel,topology.exchange(),"overflow",message());
        assertEquals(MqLabPublishOutcome.NACKED,result);counts(0);
        observed("explicit publisher NACK",Map.of("publishOutcome",result.name(),"attemptsUntilNack",attempts));
    }
    @Test @Order(4) void lostConfirmCausesSafeDuplicatePublish() throws Exception {
        var message=message();
        try(var proxy=new MqLabConfirmProxy();var proxied=broker.connect(proxy.port());var publisher=proxied.createChannel()) {
            assertEquals(MqLabPublishOutcome.UNKNOWN,MqLabBroker.publish(publisher,topology.exchange(),"work",json.writeValueAsBytes(message),message.eventId(),800));
            assertTrue(proxy.droppedAck());
        }
        publish(message);assertTrue(applyAck(delivery()));assertFalse(applyAck(delivery()));counts(1);
        observed("lost publisher confirm",Map.of("droppedActualBasicAck",true,"firstOutcome","UNKNOWN","deliveries",2));
    }
    @Test @Order(5) void duplicatesHandledByTwoConcurrentConsumers() throws Exception {
        var message=message();publish(message);publish(message);
        var start=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{
                try(var consumer=broker.connect();var worker=consumer.createChannel()) {
                    var delivery=MqLabBroker.receive(worker,topology.work());start.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));
                    boolean changed=service.apply(MqLabBroker.decode(delivery));MqLabBroker.ack(worker,topology.work(),delivery);return changed;
                }
            }));
            assertTrue(start.await(10,TimeUnit.SECONDS));go.countDown();int changes=0;
            for(var task:tasks) if(task.get(15,TimeUnit.SECONDS))changes++;
            assertEquals(1,changes);counts(1);observed("concurrent duplicate consumers",Map.of("consumers",2,"commitsWithEffect",changes));
        } finally { go.countDown();pool.shutdownNow(); }
    }
    @Test @Order(6) void conflictingPayloadIsQuarantined() throws Exception {
        var message=message();publish(message);assertTrue(applyAck(delivery()));
        publish(new MqLabMessage(1,message.eventId(),account,2,0));var conflict=delivery();
        assertThrows(IllegalArgumentException.class,()->service.apply(MqLabBroker.decode(conflict)));
        channel.basicNack(conflict.getEnvelope().getDeliveryTag(),false,false);
        var dead=MqLabBroker.receive(channel,topology.dead());assertEquals(2,MqLabBroker.decode(dead).delta());
        MqLabBroker.ack(channel,topology.dead(),dead);counts(1);observed("same event ID different payload",Map.of("extraEffects",0,"quarantined",true));
    }
    @Test @Order(7) void transactionFailureRollsBackInboxLedgerAndEffect() throws Exception {
        publish(message());var first=delivery();
        assertThrows(IllegalStateException.class,()->service.apply(MqLabBroker.decode(first),()->{throw new IllegalStateException("Injected DB failure");}));
        counts(0);channel.close();channel=connection.createChannel();var again=delivery();
        assertTrue(again.getEnvelope().isRedeliver());assertTrue(applyAck(again));counts(1);
        observed("transaction rollback then redelivery",Map.of("rolledBackAllThreeWrites",true,"redelivered",true));
    }
    private void killAt(String phase) throws Exception {
        publish(message());Path marker=Files.createTempFile(Path.of("target"),"mq-lab-phase-",".txt");Files.delete(marker);
        Path log=Files.createTempFile(Path.of("target"),"mq-lab-worker-",".log");
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var processBuilder=new ProcessBuilder(java,"-Xmx128m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                MqLabWorkerProcess.class.getName(),prefix,vhost,topology.work(),phase,marker.toAbsolutePath().toString());
        processBuilder.environment().put("TF_MQ_LAB_USER",user);processBuilder.environment().put("TF_MQ_LAB_PASSWORD",password);
        processBuilder.redirectErrorStream(true).redirectOutput(log.toFile());var process=processBuilder.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(marker) && process.isAlive() && System.nanoTime()<deadline)Thread.sleep(25);
            assertTrue(Files.exists(marker),"Child did not reach fault boundary; inspect ignored target worker log");
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            var redelivered=delivery();assertTrue(redelivered.getEnvelope().isRedeliver());
            if("before-commit".equals(phase)) { counts(0);assertTrue(applyAck(redelivered)); }
            else { counts(1);assertFalse(applyAck(redelivered)); }
            counts(1);observed("kill child "+phase,Map.of("actualChildJvmTerminated",true,"redelivered",true,"finalEffects",1));
        } finally {
            if(process.isAlive()) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            Files.deleteIfExists(marker); // Retain ignored worker diagnostic log, no credentials printed.
        }
    }
    @Test @Order(8) void killConsumerBeforeDatabaseCommit() throws Exception { killAt("before-commit"); }
    @Test @Order(9) void killConsumerAfterCommitBeforeAck() throws Exception { killAt("after-commit"); }

    private List<Long> retryThreeTimes(MqLabMessage message) throws Exception {
        publish(message);var timings=new ArrayList<Long>();
        for(int attempt=0;attempt<3;attempt++) {
            var delivery=delivery();var current=MqLabBroker.decode(delivery);assertEquals(attempt,current.attempt());
            assertThrows(IllegalStateException.class,()->service.apply(current,()->{throw new IllegalStateException("Injected transient failure");}));counts(0);
            long begin=System.nanoTime();var retry=current.retry();
            assertEquals(MqLabPublishOutcome.CONFIRMED,MqLabBroker.publish(channel,topology.retryExchange(),"retry."+retry.attempt(),retry));
            MqLabBroker.ack(channel,topology.work(),delivery);
            // Inspect the next delivery without settling it; close requeues it for the next iteration.
            var next=delivery();long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
            assertEquals(retry.attempt(),MqLabBroker.decode(next).attempt());assertTrue(elapsed>=retryMillis*(1<<attempt)-25,"TTL returned too soon");
            timings.add(elapsed);channel.close();channel=connection.createChannel();
        }
        return timings;
    }
    @Test @Order(10) void boundedBackoffThenSuccessfulRetry() throws Exception {
        var timings=retryThreeTimes(message());var last=delivery();assertEquals(3,MqLabBroker.decode(last).attempt());assertTrue(applyAck(last));counts(1);
        assertNull(channel.basicGet(topology.work(),false));observed("bounded TTL backoff then success",Map.of("observedDelayMillis",timings,"expectedMinimumMillis",List.of(retryMillis,retryMillis*2,retryMillis*4)));
    }
    @Test @Order(11) void poisonMessageDeadLettersAndControlledReplayRecovers() throws Exception {
        var timings=retryThreeTimes(message());var last=delivery();
        assertThrows(IllegalStateException.class,()->service.apply(MqLabBroker.decode(last),()->{throw new IllegalStateException("Poison fault");}));counts(0);
        channel.basicNack(last.getEnvelope().getDeliveryTag(),false,false);
        var dead=MqLabBroker.receive(channel,topology.dead());var retained=MqLabBroker.decode(dead);assertEquals(3,retained.attempt());
        assertNotNull(dead.getProps().getHeaders().get("x-death"));
        // Operator replay after fixing the injected fault: confirm before settling the retained DLQ message.
        publish(new MqLabMessage(1,retained.eventId(),retained.accountId(),retained.delta(),0));MqLabBroker.ack(channel,topology.dead(),dead);
        assertTrue(applyAck(delivery()));counts(1);assertNull(channel.basicGet(topology.dead(),false));
        observed("poison -> DLQ -> controlled replay",Map.of("attemptsBeforeDlq",4,"observedDelayMillis",timings,"replayEffect",1));
    }
    @Test @Order(12) void malformedEnvelopeIsIsolatedWithoutDatabaseEffect() throws Exception {
        byte[] invalid="{\"schemaVersion\":99}".getBytes(StandardCharsets.UTF_8);
        assertEquals(MqLabPublishOutcome.CONFIRMED,MqLabBroker.publish(channel,topology.exchange(),"work",invalid,key(),5000));
        var bad=delivery();assertThrows(RuntimeException.class,()->MqLabBroker.decode(bad));
        channel.basicNack(bad.getEnvelope().getDeliveryTag(),false,false);var dead=MqLabBroker.receive(channel,topology.dead());
        assertArrayEquals(invalid,dead.getBody());counts(0);MqLabBroker.ack(channel,topology.dead(),dead);
        observed("malformed envelope quarantine",Map.of("retainedOriginalBody",true,"effects",0));
    }
    @Test @Order(13) void confirmedPendingMessageSurvivesBrokerRestart() throws Exception {
        publish(message());channel.close();connection.close();
        Path log=Files.createTempFile(Path.of("target"),"mq-lab-restart-",".log");
        var restart=new ProcessBuilder("ssh","-i",Path.of(System.getProperty("user.home"),".ssh","ticketflow_ecs").toString(),
                "-o","BatchMode=yes","-o","StrictHostKeyChecking=yes","-o","HostKeyAlias=118.178.253.75","-o","ConnectTimeout=8",
                "root@"+System.getenv().getOrDefault("TF_ECS_HOST","118.178.253.75"),
                "cd /opt/ticketflow && docker compose restart rabbitmq").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(restart.waitFor(60,TimeUnit.SECONDS),"Broker restart timed out");assertEquals(0,restart.exitValue()); }
        finally { if(restart.isAlive())restart.destroyForcibly(); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);Exception last=null;
        while(System.nanoTime()<deadline) {
            try {connection=broker.connect();last=null;break;} catch(Exception unavailable) {last=unavailable;Thread.sleep(500);}
        }
        if(last!=null)throw new IllegalStateException("Broker did not recover after restart",last);
        channel=connection.createChannel();channel.queueDeclarePassive(topology.work());assertTrue(applyAck(delivery()));counts(1);
        observed("broker restart persistence",Map.of("actualContainerRestart",true,"queueRedeclared",false,"confirmedMessageRecovered",true));
    }
}
