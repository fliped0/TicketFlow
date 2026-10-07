package com.ticketflow.config;
import com.ticketflow.model.entity.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AsyncRedisGateway {
    private final StringRedisTemplate redis;private final RedisFeatureProperties settings;private final JsonMapper json;
    public AsyncRedisGateway(StringRedisTemplate redis,RedisFeatureProperties settings,JsonMapper json) {this.redis=redis;this.settings=settings;this.json=json;}
    public List<String> keys(long session,long epoch) {
        String base=settings.namespace()+":async:{"+session+"}:e:"+epoch+":";
        return List.of(base+"gate",base+"free",base+"tokens",base+"slots",base+"requests",base+"tentative",base+"seq",base+"rate");
    }
    private void outside() {if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Redis in database transaction");}
    private static final DefaultRedisScript<Long> INIT=new DefaultRedisScript<>("""
        for _,k in ipairs(KEYS) do if redis.call('EXISTS',k)==1 then return 0 end end
        local cfg=cjson.decode(ARGV[1]); local free=cjson.decode(ARGV[2]); local seq=cjson.decode(ARGV[3])
        redis.call('HSET',KEYS[1],'epoch',cfg.epoch,'phase','PAUSED','start',cfg.start,'finish',cfg.finish,'starts',cfg.starts)
        for tier,count in pairs(free) do redis.call('HSET',KEYS[2],tier,count) end
        for tier,count in pairs(seq) do redis.call('HSET',KEYS[7],tier,count) end
        return 1
        """,Long.class);
    public void initialize(long session,long epoch,long start,long finish,long starts,Map<String,Long> free,Map<String,Long> seq) {
        outside();if(free.isEmpty())throw new IllegalStateException("No tiers to initialize");
        Long result=redis.execute(INIT,keys(session,epoch),json.writeValueAsString(Map.of("epoch",epoch,"start",start,"finish",finish,"starts",starts)),json.writeValueAsString(free),json.writeValueAsString(seq));
        if(!Long.valueOf(1).equals(result))throw new IllegalStateException("Epoch already exists");
    }
    public void ready(long session,long epoch) {outside();Long ok=redis.execute(new DefaultRedisScript<>("if redis.call('HGET',KEYS[1],'epoch')~=ARGV[1] then return 0 end redis.call('HSET',KEYS[1],'phase','READY') return 1",Long.class),List.of(keys(session,epoch).get(0)),Long.toString(epoch));if(!Long.valueOf(1).equals(ok))throw new IllegalStateException("Redis epoch missing");}
    public long freeze(long session,long epoch) {outside();Long count=redis.execute(new DefaultRedisScript<>("if redis.call('EXISTS',KEYS[1])==1 then redis.call('HSET',KEYS[1],'phase','PAUSED') end return redis.call('ZCARD',KEYS[2])",Long.class),List.of(keys(session,epoch).get(0),keys(session,epoch).get(5)));return count==null?-1:count;}
    private static final DefaultRedisScript<List> RESERVE=new DefaultRedisScript<>("""
        local types={'hash','hash','hash','hash','hash','zset','hash','hash'}
        for i,k in ipairs(KEYS) do local t=redis.call('TYPE',k).ok;if t~='none' and t~=types[i] then return {'MISSING'} end end
        if redis.call('HGET',KEYS[1],'epoch')~=ARGV[1] or redis.call('HGET',KEYS[1],'phase')~='READY' then return {'ASYNC_PAUSED'} end
        local user,key,hash,tier=ARGV[2],ARGV[3],ARGV[4],ARGV[5]
        local old=redis.call('HGET',KEYS[5],user..':'..key)
        if old then
          local raw=redis.call('HGET',KEYS[3],old);if not raw then return {'MISSING'} end
          local info=cjson.decode(raw);if not info.complete then return {'MISSING'} end
          if info.hash~=hash then return {'IDEMPOTENCY_CONFLICT'} end
          return {'REPLAY',info.id,old,tostring(info.at)}
        end
        local time=redis.call('TIME');local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
        if math.abs(now-tonumber(ARGV[8]))>1000 then return {'CLOCK_SKEW'} end
        local start=tonumber(redis.call('HGET',KEYS[1],'start'));local finish=tonumber(redis.call('HGET',KEYS[1],'finish'));local starts=tonumber(redis.call('HGET',KEYS[1],'starts'))
        if not start or not finish or not starts then return {'MISSING'} end
        if now<start then return {'SALE_NOT_STARTED'} end
        if now>=finish or now>=starts then return {'SALE_ENDED'} end
        local free=tonumber(redis.call('HGET',KEYS[2],tier));if not free or free<0 then return {'MISSING'} end
        local window=math.floor(now/tonumber(ARGV[11]));local uk='u:'..user;local sk='s';local reset=redis.call('HGET',KEYS[8],'window')~=tostring(window)
        local uc=reset and 0 or tonumber(redis.call('HGET',KEYS[8],uk) or '0');local sc=reset and 0 or tonumber(redis.call('HGET',KEYS[8],sk) or '0')
        if not uc or not sc then return {'MISSING'} end
        if uc>=tonumber(ARGV[9]) or sc>=tonumber(ARGV[10]) then return {'RATE_LIMITED'} end
        local rejected=nil
        if redis.call('HEXISTS',KEYS[4],user)==1 then rejected='PURCHASE_LIMIT' elseif free==0 then rejected='SOLD_OUT' end
        local id,token=ARGV[6],ARGV[7]
        if redis.call('HEXISTS',KEYS[3],token)==1 then return {'MISSING'} end
        if reset then redis.call('DEL',KEYS[8]);redis.call('HSET',KEYS[8],'window',window);redis.call('PEXPIRE',KEYS[8],tonumber(ARGV[11])*2) end
        redis.call('HINCRBY',KEYS[8],uk,1);redis.call('HINCRBY',KEYS[8],sk,1)
        if rejected then return {rejected} end
        local info={id=id,user=user,key=key,hash=hash,tier=tier,at=now,state='TENTATIVE',complete=false}
        redis.call('HINCRBY',KEYS[2],tier,-1)
        redis.call('HSET',KEYS[3],token,cjson.encode(info));redis.call('HSET',KEYS[4],user,token);redis.call('HSET',KEYS[5],user..':'..key,token)
        redis.call('ZADD',KEYS[6],now,token)
        info.complete=true;redis.call('HSET',KEYS[3],token,cjson.encode(info))
        return {'RESERVED',id,token,tostring(now)}
        """,List.class);
    public AsyncReservation reserve(long session,long epoch,long user,String keyHash,String hash,long tier,String id,String token,long dbMillis) {
        outside();List<?> result=redis.execute(RESERVE,keys(session,epoch),Long.toString(epoch),Long.toString(user),keyHash,hash,Long.toString(tier),id,token,Long.toString(dbMillis),Integer.toString(settings.userLimit()),Integer.toString(settings.sessionLimit()),Integer.toString(settings.windowMillis()));
        if(result==null || result.isEmpty())throw new IllegalStateException("Missing reservation response");
        String code=result.get(0).toString();return new AsyncReservation(code,result.size()>1?result.get(1).toString():id,result.size()>2?result.get(2).toString():null,result.size()>3?Long.parseLong(result.get(3).toString()):0);
    }
    private static final DefaultRedisScript<String> PROJECT=new DefaultRedisScript<>("""
        local types={'hash','hash','hash','hash','hash','zset','hash','hash'}
        for i,k in ipairs(KEYS) do local t=redis.call('TYPE',k).ok;if t~='none' and t~=types[i] then return 'MISSING' end end
        if redis.call('HGET',KEYS[1],'epoch')~=ARGV[1] then return 'MISSING' end
        local last=tonumber(redis.call('HGET',KEYS[7],ARGV[2]));local seq=tonumber(ARGV[3]);if not last then return 'MISSING' end
        if seq<=last then return 'DUPLICATE' end;if seq~=last+1 then return 'GAP' end
        local raw=redis.call('HGET',KEYS[3],ARGV[4]);if not raw then return 'MISSING' end
        local info=cjson.decode(raw);if not info.complete or info.tier~=ARGV[2] or info.id~=ARGV[6] or info.user~=ARGV[5] then return 'MISSING' end
        if ARGV[7]=='RELEASE' then
          local free=tonumber(redis.call('HGET',KEYS[2],ARGV[2]));if not free or free<0 then return 'MISSING' end
          if info.state~='RELEASED' then redis.call('HINCRBY',KEYS[2],ARGV[2],1);info.state='RELEASED' end
          if redis.call('HGET',KEYS[4],ARGV[5])==ARGV[4] then redis.call('HDEL',KEYS[4],ARGV[5]) end
        elseif ARGV[7]=='ACTIVATE' then
          if info.state=='TENTATIVE' then info.state='ACCEPTED' end
        elseif ARGV[7]=='MATERIALIZE' then info.state='ORDER';info.order=ARGV[8]
        else return 'MISSING' end
        redis.call('HSET',KEYS[3],ARGV[4],cjson.encode(info));redis.call('ZREM',KEYS[6],ARGV[4]);redis.call('HSET',KEYS[7],ARGV[2],seq)
        return 'APPLIED'
        """,String.class);
    public String project(OutboxEvent e,String token,long user,String request,String order) {
        outside();return redis.execute(PROJECT,keys(e.session(),e.epoch()),Long.toString(e.epoch()),Long.toString(e.tier()),Long.toString(e.sequence()),token,Long.toString(user),request,e.type(),order==null?"":order);
    }
}
