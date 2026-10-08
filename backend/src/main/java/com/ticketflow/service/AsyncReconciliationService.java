package com.ticketflow.service;

import com.ticketflow.config.AsyncRedisGateway;
import com.ticketflow.mapper.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AsyncReconciliationService {
    private final AsyncTransactions tx;
    private final AsyncRecoveryMapper mapper;
    private final AsyncRecoveryService recovery;
    private final AsyncGateMapper gates;
    private final AsyncRedisGateway redis;
    public AsyncReconciliationService(AsyncTransactions tx,AsyncRecoveryMapper mapper,AsyncRecoveryService recovery,AsyncGateMapper gates,AsyncRedisGateway redis) {this.tx=tx;this.mapper=mapper;this.recovery=recovery;this.gates=gates;this.redis=redis;}
    public Map<String,Object> inspect(long session) {
        var differences=tx.snapshot(()->mapper.differences(session));
        if(!differences.isEmpty())return Map.of("session",session,"observedAt",Instant.now().toString(),"status","DB_MISMATCH","differences",differences);
        for(int attempt=0;attempt<3;attempt++) {
            var before=tx.snapshot(()->{var g=gates.read(session);return recovery.snapshot(session,g.epoch(),0,"observation");});
            var first=redis.differences(before);var second=redis.differences(before);
            var after=tx.snapshot(()->{var g=gates.read(session);return recovery.snapshot(session,g.epoch(),0,"observation");});
            if(!before.equals(after) || !first.equals(second))continue;
            return Map.of("session",session,"epoch",after.epoch(),"sequence",after.sequence(),"observedAt",Instant.now().toString(),"status",first.isEmpty()?"OBSERVED_MATCH":"NEEDS_ATTENTION","differences",first);
        }
        return Map.of("session",session,"observedAt",Instant.now().toString(),"status","CHANGING_RETRY","differences",List.of());
    }
}
