package com.ticketflow.service;

import com.ticketflow.config.AsyncRedisGateway;
import com.ticketflow.mapper.*;
import com.ticketflow.model.entity.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AsyncRecoveryService {
    private final AsyncTransactions tx;
    private final AsyncGateMapper gates;
    private final AsyncRecoveryMapper recovery;
    private final AsyncRequestMapper requests;
    private final TradeMapper users;
    private final OrderMapper orders;
    private final InventoryService inventory;
    private final CatalogMapper catalog;
    private final AsyncEvents events;
    private final DatabaseClock clock;
    private final AsyncRedisGateway redis;
    private final MeterRegistry metrics;
    private final AsyncAlerts alerts;
    private long cursor;

    public AsyncRecoveryService(AsyncTransactions tx,AsyncGateMapper gates,AsyncRecoveryMapper recovery,
            AsyncRequestMapper requests,TradeMapper users,OrderMapper orders,InventoryService inventory,
            CatalogMapper catalog,AsyncEvents events,DatabaseClock clock,AsyncRedisGateway redis,MeterRegistry metrics,AsyncAlerts alerts) {
        this.tx=tx;this.gates=gates;this.recovery=recovery;this.requests=requests;this.users=users;this.orders=orders;
        this.inventory=inventory;this.catalog=catalog;this.events=events;this.clock=clock;this.redis=redis;this.metrics=metrics;this.alerts=alerts;
    }

    public void pause(long session,long epoch) {
        tx.execute(()->{var g=gates.lock(session,true);if(g.epoch()==epoch && "READY".equals(g.phase()))gates.pause(session);return null;});
    }

    /** A terminal DB fence precedes any release. Existing accepted/ordered receipts are never released here. */
    public void seal(long session,long epoch,AsyncToken token,boolean maintenance) {
        tx.execute(()->{
            var gate=gates.lock(session,false);
            if(gate.epoch()!=epoch || (!maintenance && !"READY".equals(gate.phase())))return null;
            if(!users.lockOwner(token.user()))throw new IllegalStateException("Missing token owner");
            if(token.key()==null)throw new IllegalStateException("Legacy orphan receipt lacks request key");
            var existing=requests.byKey(token.user(),token.key(),true);
            if(existing!=null && token.token().equals(existing.token())) {
                if(!existing.id().equals(token.id()) || !existing.hash().equals(token.hash()) || existing.tierId()!=token.tier() || existing.sessionId()!=session)throw new IllegalStateException("Receipt differs from durable request");
                return null;
            }
            var route=orders.lockCatalog(token.tier());
            if(route==null || route.sessionId()!=session || !TradeExecutor.hash("ASYNC:v1\ntierId="+token.tier()+"\nquantity=1").equals(token.hash()))throw new IllegalStateException("Invalid orphan routing");
            orders.hasSlot(token.user(),session);requests.hasSlot(token.user(),session);inventory.lock(token.tier());requests.lockBalance(token.tier());
            var now=clock.nowUtc();
            if(!maintenance && now.toInstant(ZoneOffset.UTC).toEpochMilli()<token.at()+10_000)return null;
            if(existing==null)requests.insert(token.id(),token.user(),session,token.tier(),token.key(),token.hash(),token.token(),epoch,"REJECTED",now,null,null,"ADMISSION_EXPIRED",409);
            String event=UUID.nameUUIDFromBytes(("RELEASE:"+token.token()).getBytes(StandardCharsets.UTF_8)).toString();
            events.projection(event,token.id(),token.user(),session,token.tier(),epoch,token.token(),"RELEASE",null,now);
            return null;
        });
    }

    public void sweepOrphans(long session) {
        var gate=gates.read(session);
        for(var token:redis.tentative(session,gate.epoch(),10_000,0))seal(session,gate.epoch(),token,false);
    }

    public AsyncSnapshot snapshot(long session,long epoch,long version,String owner) {
        var differences=recovery.differences(session);
        if(!differences.isEmpty())throw new IllegalStateException("Async reconciliation mismatch: "+differences);
        var free=new LinkedHashMap<String,Long>();var seq=new LinkedHashMap<String,Long>();
        for(var row:requests.bootstrap(session)) {
            String tier=row.get("id").toString();free.put(tier,((Number)row.get("available")).longValue()-((Number)row.get("queued_count")).longValue());seq.put(tier,((Number)row.get("projection_version")).longValue());
        }
        if(free.isEmpty())throw new IllegalStateException("No tiers to rebuild");
        var times=catalog.session(session,false);
        return new AsyncSnapshot(session,epoch,version,owner,times.saleStartAt().toInstant(ZoneOffset.UTC).toEpochMilli(),times.saleEndAt().toInstant(ZoneOffset.UTC).toEpochMilli(),times.startsAt().toInstant(ZoneOffset.UTC).toEpochMilli(),free,seq,recovery.activeTokens(session));
    }

    public boolean rebuild(long session) {
        String owner=UUID.randomUUID().toString();
        var old=gates.read(session);
        Long version=tx.execute(()->{
            var gate=gates.lock(session,true);
            if(!"ASYNC".equals(gate.mode()) || gate.epoch()!=old.epoch())return null;
            return recovery.claim(session,owner);
        });
        if(version==null)return false;
        long started=System.nanoTime();
        try {
            // DB pause is committed first: even a Lua call already in flight can no longer be admitted.
            long frozen=redis.freeze(session,old.epoch());
            try {
                if(frozen<0)throw new com.ticketflow.common.exception.AsyncViewCorruptedException("Invalid Redis gate/index type");
                for(int offset=0;;offset+=100) {
                    var tokens=redis.tentative(session,old.epoch(),0,offset);
                    for(var token:tokens)seal(session,old.epoch(),token,true);
                    if(tokens.size()<100)break;
                    if(System.nanoTime()-started>25_000_000_000L)throw new IllegalStateException("Maintenance budget exceeded");
                }
            } catch(com.ticketflow.common.exception.AsyncViewCorruptedException lost) {
                // No release is inferred. Retire the entire old epoch under the DB gate;
                // all committed requests survive in the snapshot and late old admissions are fenced.
                alerts.raise("REDIS_VIEW_LOST",Long.toString(session),"OLD_EPOCH_RETIRED_WITHOUT_RELEASE");
            }
            var snap=tx.execute(()->{
                gates.lock(session,true);
                if(!recovery.owns(session,old.epoch(),version,owner))throw new IllegalStateException("Maintenance fenced");
                recovery.advance(session,old.epoch(),version,owner);
                var result=snapshot(session,old.epoch()+1,version,owner);
                for(String id:recovery.pending(session))events.broker(requests.get(id,false),clock.nowUtc());
                return result;
            });
            redis.restore(snap);
            tx.execute(()->{
                gates.lock(session,true);
                if(!recovery.owns(session,snap.epoch(),version,owner))throw new IllegalStateException("Maintenance fenced");
                recovery.ready(session,snap.epoch(),version,owner);return null;
            });
            redis.ready(session,snap.epoch());
            alerts.resolve("REBUILD",Long.toString(session));
            metrics.counter("ticketflow.async.recovery","outcome","REBUILT").increment();
            LoggerFactory.getLogger(getClass()).info("async_rebuilt sessionId={} epoch={} elapsedMs={}",session,snap.epoch(),(System.nanoTime()-started)/1_000_000);
            return true;
        } catch(RuntimeException failure) {
            // Keep a failed lease for bounded retry backoff. A subsequent coordinator uses a fresh epoch.
            metrics.counter("ticketflow.async.recovery","outcome","FAILED").increment();
            alerts.raise("REBUILD",Long.toString(session),failure.getClass().getSimpleName());
            LoggerFactory.getLogger(getClass()).error("async_rebuild_failed sessionId={} type={}",session,failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public synchronized void tick() {
        long end=System.nanoTime()+5_000_000_000L;
        var sessions=recovery.sessions(cursor);if(sessions.isEmpty()){cursor=0;return;}
        for(long session:sessions) {
            if(System.nanoTime()>end)break;cursor=session;
            var gate=gates.read(session);
            try {
                if("READY".equals(gate.phase())) {
                    var tiers=requests.bootstrap(session).stream().map(r->r.get("id").toString()).toList();
                    if(redis.healthy(session,gate.epoch(),tiers)){sweepOrphans(session);continue;}
                    pause(session,gate.epoch());
                }
                rebuild(session);
            } catch(RuntimeException failure) {
                pause(session,gate.epoch());
                LoggerFactory.getLogger(getClass()).warn("async_recovery_pending sessionId={} type={}",session,failure.getClass().getSimpleName());
            }
        }
    }
}
