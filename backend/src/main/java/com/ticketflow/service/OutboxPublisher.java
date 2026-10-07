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
    public OutboxPublisher(AsyncTransactions tx,OutboxMapper outbox,AsyncGateMapper gates,DatabaseClock clock,AsyncBrokerGateway broker,AsyncRedisGateway redis,JsonMapper json) {this.tx=tx;this.outbox=outbox;this.gates=gates;this.clock=clock;this.broker=broker;this.redis=redis;this.json=json;}
    public synchronized void tick() {
        long end=System.nanoTime()+5_000_000_000L;
        for(String destination:java.util.List.of("REDIS","BROKER")) {
            for(int i=0;i<100 && System.nanoTime()<end;i++) {
                var event=tx.execute(()->outbox.claim(destination,owner,clock.nowUtc()));if(event==null)break;boolean success=false;
                try {
                    if("BROKER".equals(destination))success=broker.publish(event);
                    else {
                        var gate=gates.read(event.session());
                        if(gate.epoch()>event.epoch())success=true;
                        else if(gate.epoch()==event.epoch() && "READY".equals(gate.phase())) {
                            var payload=json.readTree(event.payload());String result=redis.project(event,payload.path("token").asString(),Long.parseLong(payload.path("userId").asString()),payload.path("requestId").asString(),payload.path("orderId").asString());
                            success=java.util.List.of("APPLIED","DUPLICATE").contains(result);
                            if("MISSING".equals(result))tx.execute(()->{gates.lock(event.session(),true);gates.pause(event.session());return null;});
                        }
                    }
                } catch(Exception unavailable) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("outbox_dispatch_failed eventId={} destination={} type={}",event.id(),destination,unavailable.getClass().getSimpleName());}
                boolean confirmed=success;tx.execute(()->{outbox.settle(event,owner,confirmed,clock.nowUtc());return null;});
                if(!success)break;
            }
        }
    }
}
