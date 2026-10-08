package com.ticketflow.service;

import com.ticketflow.config.AsyncBrokerGateway;
import com.ticketflow.mapper.AsyncOperationsMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Local CLI only: the operator needs the existing scoped deployment credentials. */
@Service
public class AsyncOperationsService {
    private final AsyncRecoveryService recovery;
    private final AsyncReconciliationService reconciliation;
    private final AsyncOperationsMapper operations;
    private final AsyncBrokerGateway broker;
    private final AsyncDeadReplayService replay;
    public AsyncOperationsService(AsyncRecoveryService recovery,AsyncReconciliationService reconciliation,AsyncOperationsMapper operations,AsyncBrokerGateway broker,AsyncDeadReplayService replay) {
        this.recovery=recovery;this.reconciliation=reconciliation;this.operations=operations;this.broker=broker;this.replay=replay;
    }
    public Object execute(String command,long session,String event) throws Exception {
        return switch(command) {
            case "audit" -> {if(session<=0)throw new IllegalArgumentException("Positive session required");yield reconciliation.inspect(session);}
            case "rebuild" -> {if(session<=0)throw new IllegalArgumentException("Positive session required");yield Map.of("session",session,"rebuilt",recovery.rebuild(session));}
            case "alerts" -> operations.alerts();
            case "replay" -> {
                boolean done=broker.replayDead(event,replay::replay);
                if(done)operations.resolve("DEAD_LETTER",event);
                yield Map.of("event",event,"replayed",done);
            }
            default -> throw new IllegalArgumentException("Unknown async operation");
        };
    }
}
