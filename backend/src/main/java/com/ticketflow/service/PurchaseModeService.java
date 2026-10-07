package com.ticketflow.service;
import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.config.*;
import com.ticketflow.mapper.*;
import com.ticketflow.model.dto.PurchaseModeDTO;
import com.ticketflow.model.vo.PurchaseModeVO;
import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class PurchaseModeService {
    private final AsyncTransactions tx;private final AsyncGateMapper gates;private final AsyncRequestMapper requests;
    private final CatalogMapper catalog;private final AsyncRedisGateway redis;private final AsyncProperties settings;private final JsonMapper json;
    public PurchaseModeService(AsyncTransactions tx,AsyncGateMapper gates,AsyncRequestMapper requests,CatalogMapper catalog,AsyncRedisGateway redis,AsyncProperties settings,JsonMapper json) {
        this.tx=tx;this.gates=gates;this.requests=requests;this.catalog=catalog;this.redis=redis;this.settings=settings;this.json=json;
    }
    private static BusinessException error(int status,String code) {return new BusinessException(status,code,"场次模式暂不能修改");}
    public PurchaseModeVO change(long actor,long session,PurchaseModeDTO input) {
        if(input==null || input.mode()==null || !List.of("SYNC","ASYNC").contains(input.mode()) || input.expectedVersion()==null || input.expectedVersion()<0)throw error(400,"VALIDATION_ERROR");
        if(!settings.enabled() && "ASYNC".equals(input.mode()))throw error(503,"ASYNC_DISABLED");
        var old=gates.read(session);if(old==null)throw error(404,"NOT_FOUND");
        // Reject invalid maintenance before touching a live Redis gate.
        tx.execute(()->{
            gates.lock(session,false);if(!catalog.lockActor(actor))throw error(403,"FORBIDDEN");
            var before=catalog.session(session,false);var now=catalog.now();
            if(before.version()!=input.expectedVersion())throw error(409,"VERSION_CONFLICT");
            if(!now.isBefore(before.freezeAt()) || !now.isBefore(before.saleStartAt()) || requests.history(session))throw error(409,"CONFIG_FROZEN");
            return null;
        });
        if("ASYNC".equals(old.mode())) {
            try {if(redis.freeze(session,old.epoch())!=0)throw error(409,"ASYNC_IN_FLIGHT");}
            catch(BusinessException e){throw e;}catch(RuntimeException e){throw error(503,"DEPENDENCY_UNAVAILABLE");}
        }
        // Snapshot stays protected by PAUSED between this commit and Redis initialization.
        var gate=tx.execute(()->{
            var locked=gates.lock(session,true);if(!catalog.lockActor(actor))throw error(403,"FORBIDDEN");
            var route=catalog.session(session,false);catalog.event(route.eventId(),true);var before=catalog.session(session,true);
            if(before.version()!=input.expectedVersion())throw error(409,"VERSION_CONFLICT");
            var now=catalog.now();if(!now.isBefore(before.freezeAt()) || !now.isBefore(before.saleStartAt()) || requests.history(session))throw error(409,"CONFIG_FROZEN");
            catalog.setPurchaseMode(session,input.mode());gates.changed(session);
            var after=catalog.session(session,false);catalog.audit(actor,"PURCHASE_MODE","SESSION",session,json.writeValueAsString(Map.of("mode",locked.mode(),"version",before.version())),json.writeValueAsString(Map.of("mode",input.mode(),"version",after.version())),MDC.get("traceId")==null?"server":MDC.get("traceId"));catalog.advanceRevision();return gates.read(session);
        });
        if("ASYNC".equals(gate.mode())) {
            try {
                var rows=requests.bootstrap(session);var free=new LinkedHashMap<String,Long>();var seq=new LinkedHashMap<String,Long>();
                for(var row:rows){long queued=((Number)row.get("queued_count")).longValue();if(queued!=0)throw new IllegalStateException("Nonempty bootstrap");String tier=row.get("id").toString();free.put(tier,((Number)row.get("available")).longValue());seq.put(tier,((Number)row.get("projection_version")).longValue());}
                var times=catalog.session(session,false);redis.initialize(session,gate.epoch(),times.saleStartAt().toInstant(ZoneOffset.UTC).toEpochMilli(),times.saleEndAt().toInstant(ZoneOffset.UTC).toEpochMilli(),times.startsAt().toInstant(ZoneOffset.UTC).toEpochMilli(),free,seq);
                tx.execute(()->{var current=gates.lock(session,true);if(current.epoch()!=gate.epoch() || !"ASYNC".equals(current.mode()))throw error(409,"VERSION_CONFLICT");gates.ready(session,gate.epoch());return null;});
                redis.ready(session,gate.epoch());
            } catch(RuntimeException failure) {tx.execute(()->{var current=gates.lock(session,true);if(current.epoch()==gate.epoch())gates.pause(session);return null;});throw error(503,"ASYNC_PAUSED");}
        }
        var current=gates.read(session);return new PurchaseModeVO(Long.toString(session),current.mode(),catalog.session(session,false).version(),current.phase());
    }
}
