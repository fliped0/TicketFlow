package com.ticketflow.service;
import com.ticketflow.config.*;
import com.ticketflow.mapper.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class OutboxPublisher {
    private final AsyncTransactions tx;private final OutboxMapper outbox;private final AsyncGateMapper gates;private final DatabaseClock clock;
    private final AsyncBrokerGateway broker;private final AsyncRedisGateway redis;private final JsonMapper json;private final String owner=UUID.randomUUID().toString();
    private final AsyncAlerts alerts;
    public OutboxPublisher(AsyncTransactions tx,OutboxMapper outbox,AsyncGateMapper gates,DatabaseClock clock,AsyncBrokerGateway broker,AsyncRedisGateway redis,JsonMapper json,AsyncAlerts alerts) {this.tx=tx;this.outbox=outbox;this.gates=gates;this.clock=clock;this.broker=broker;this.redis=redis;this.json=json;this.alerts=alerts;}
    public synchronized void tick() {
        for(String destination:java.util.List.of("REDIS","BROKER")) {
            long end=System.nanoTime()+5_000_000_000L;
            for(int i=0;i<100 && System.nanoTime()<end;i++) {
                var event=tx.execute(()->outbox.claim(destination,owner,clock.nowUtc()));if(event==null)break;boolean success=false;
                try {
                    if("BROKER".equals(destination)) {
                        var gate=gates.read(event.session());
                        // The epoch transition atomically queued replacement work; do not let
                        // obsolete messages delay the new epoch's 30-second deadline.
                        success=gate.epoch()>event.epoch() || broker.publish(event);
                    }
                    else {
                        var gate=gates.read(event.session());
                        if(gate.epoch()>event.epoch())success=true;
                        else if(gate.epoch()==event.epoch() && "READY".equals(gate.phase())) {
                            String result=project(event);
                            if("GAP".equals(result)) {
                                long last=redis.sequence(event.session(),event.epoch(),event.tier());
                                var missing=outbox.projections(event.tier(),last,event.sequence());
                                if(missing.isEmpty() || missing.get(0).sequence()!=last+1)result="MISSING";
                                else for(var predecessor:missing) {
                                    if(predecessor.epoch()!=event.epoch() || predecessor.sequence()!=last+1){result="MISSING";break;}
                                    result=project(predecessor);
                                    if(!java.util.List.of("APPLIED","DUPLICATE").contains(result))break;
                                    last=predecessor.sequence();
                                }
                                if(java.util.List.of("APPLIED","DUPLICATE").contains(result) && last<event.sequence())result="GAP";
                            }
                            success=java.util.List.of("APPLIED","DUPLICATE").contains(result);
                            if("MISSING".equals(result))tx.execute(()->{var current=gates.lock(event.session(),true);if(current.epoch()==event.epoch() && "READY".equals(current.phase()))gates.pause(event.session());return null;});
                        }
                    }
                } catch(Exception unavailable) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("outbox_dispatch_failed eventId={} destination={} type={}",event.id(),destination,unavailable.getClass().getSimpleName());}
                boolean confirmed=success;tx.execute(()->{outbox.settle(event,owner,confirmed,clock.nowUtc());return null;});
                if(success)alerts.resolve("OUTBOX",event.id());
                else if(event.attempts()>=5)alerts.raise("OUTBOX",event.id(),destination+"_RETRY_EXHAUSTED");
                // The failed event now has a bounded backoff; continue with unrelated work.
            }
        }
    }
    private String project(com.ticketflow.model.entity.OutboxEvent event) {
        var payload=json.readTree(event.payload());
        return redis.project(event,payload.path("token").asString(),Long.parseLong(payload.path("userId").asString()),payload.path("requestId").asString(),payload.path("orderId").asString());
    }
}
