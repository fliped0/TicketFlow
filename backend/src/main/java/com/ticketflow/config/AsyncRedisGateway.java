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
    public long freeze(long session,long epoch) {outside();Long count=redis.execute(new DefaultRedisScript<>("local g=redis.call('TYPE',KEYS[1]).ok;if g=='hash' then redis.call('HSET',KEYS[1],'phase','PAUSED') elseif g~='none' then return -2 end local t=redis.call('TYPE',KEYS[2]).ok;if t~='none' and t~='zset' then return -2 end return redis.call('ZCARD',KEYS[2])",Long.class),List.of(keys(session,epoch).get(0),keys(session,epoch).get(5)));return count==null?-1:count;}
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
        local info={id=id,user=user,key=key,requestKey=ARGV[12],hash=hash,tier=tier,at=now,state='TENTATIVE',complete=false}
        redis.call('HINCRBY',KEYS[2],tier,-1)
        redis.call('HSET',KEYS[3],token,cjson.encode(info));redis.call('HSET',KEYS[4],user,token);redis.call('HSET',KEYS[5],user..':'..key,token)
        redis.call('ZADD',KEYS[6],now,token)
        info.complete=true;redis.call('HSET',KEYS[3],token,cjson.encode(info))
        return {'RESERVED',id,token,tostring(now)}
        """,List.class);
    public AsyncReservation reserve(long session,long epoch,long user,String key,String hash,long tier,String id,String token,long dbMillis) {
        outside();List<?> result=redis.execute(RESERVE,keys(session,epoch),Long.toString(epoch),Long.toString(user),com.ticketflow.service.TradeExecutor.hash(key),hash,Long.toString(tier),id,token,Long.toString(dbMillis),Integer.toString(settings.userLimit()),Integer.toString(settings.sessionLimit()),Integer.toString(settings.windowMillis()),key);
        if(result==null || result.isEmpty())throw new IllegalStateException("Missing reservation response");
        String code=result.get(0).toString();return new AsyncReservation(code,result.size()>1?result.get(1).toString():id,result.size()>2?result.get(2).toString():null,result.size()>3?Long.parseLong(result.get(3).toString()):0);
    }
    private static final DefaultRedisScript<String> PROJECT=new DefaultRedisScript<>("""
        local types={'hash','hash','hash','hash','hash','zset','hash','hash'}
        for i,k in ipairs(KEYS) do local t=redis.call('TYPE',k).ok;if t~='none' and t~=types[i] then return 'MISSING' end end
        if redis.call('HGET',KEYS[1],'epoch')~=ARGV[1] then return 'MISSING' end
        if redis.call('HGET',KEYS[1],'phase')~='READY' then return 'PAUSED' end
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

    public long sequence(long session,long epoch,long tier) {
        outside();Object value=redis.opsForHash().get(keys(session,epoch).get(6),Long.toString(tier));
        if(value==null)throw new IllegalStateException("Missing projection watermark");return Long.parseLong(value.toString());
    }

    /** The receipt/index is read atomically; malformed or incomplete receipts freeze recovery. */
    private static final DefaultRedisScript<List> TENTATIVES=new DefaultRedisScript<>("""
        local types={'hash','hash','hash','hash','hash','zset','hash','hash'}
        for i,k in ipairs(KEYS) do local t=redis.call('TYPE',k).ok;if t~='none' and t~=types[i] then return {'INVALID'} end end
        local time=redis.call('TIME');local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
        local ids=redis.call('ZRANGEBYSCORE',KEYS[6],'-inf',now-tonumber(ARGV[1]),'LIMIT',tonumber(ARGV[2]),100)
        local result={}
        for _,id in ipairs(ids) do
          local raw=redis.call('HGET',KEYS[3],id);if not raw then return {'INVALID'} end
          local info=cjson.decode(raw)
          if not info.complete or not info.requestKey or info.state~='TENTATIVE' then return {'INVALID'} end
          table.insert(result,id);table.insert(result,raw)
        end
        return result
        """,List.class);
    public List<AsyncToken> tentative(long session,long epoch,long minimumAge,int offset) {
        outside();List<?> values=redis.execute(TENTATIVES,keys(session,epoch),Long.toString(minimumAge),Integer.toString(offset));
        if(values==null || values.size()%2!=0)throw new com.ticketflow.common.exception.AsyncViewCorruptedException("Invalid tentative index");
        var result=new ArrayList<AsyncToken>();
        for(int i=0;i<values.size();i+=2) {
            var p=json.readTree(values.get(i+1).toString());String key=p.path("requestKey").asString();
            com.ticketflow.service.TradeExecutor.validateKey(key);
            String id=p.path("id").asString(),token=values.get(i).toString();UUID.fromString(id);UUID.fromString(token);
            if(!com.ticketflow.service.TradeExecutor.hash(key).equals(p.path("key").asString()))throw new IllegalStateException("Receipt key mismatch");
            result.add(new AsyncToken(token,id,Long.parseLong(p.path("user").asString()),key,p.path("hash").asString(),Long.parseLong(p.path("tier").asString()),p.path("at").asLong(),p.path("state").asString(),null));
        }
        return result;
    }

    public boolean healthy(long session,long epoch,Collection<String> tiers) {
        outside();var k=keys(session,epoch);var gate=redis.opsForHash().entries(k.get(0));
        if(!Long.toString(epoch).equals(gate.get("epoch")) || !"READY".equals(gate.get("phase")))return false;
        var free=redis.opsForHash().entries(k.get(1));var seq=redis.opsForHash().entries(k.get(6));
        for(String tier:tiers)if(!free.containsKey(tier) || !seq.containsKey(tier) || Long.parseLong(free.get(tier).toString())<0)return false;
        return true;
    }

    private static final DefaultRedisScript<Long> RESTORE=new DefaultRedisScript<>("""
        for _,k in ipairs(KEYS) do if redis.call('EXISTS',k)==1 then return 0 end end
        local cfg=cjson.decode(ARGV[1]);local free=cjson.decode(ARGV[2]);local seq=cjson.decode(ARGV[3]);local tokens=cjson.decode(ARGV[4])
        redis.call('HSET',KEYS[1],'epoch',cfg.epoch,'phase','PAUSED','start',cfg.start,'finish',cfg.finish,'starts',cfg.starts,'snapshot',cfg.version,'owner',cfg.owner)
        for tier,count in pairs(free) do redis.call('HSET',KEYS[2],tier,count) end
        for tier,count in pairs(seq) do redis.call('HSET',KEYS[7],tier,count) end
        for _,info in ipairs(tokens) do
          local token=info.token;info.complete=true;info.user=tostring(info.user);info.tier=tostring(info.tier)
          info.requestKey=info.key;info.key=info.keyHash;info.keyHash=nil
          redis.call('HSET',KEYS[3],token,cjson.encode(info));redis.call('HSET',KEYS[4],info.user,token);redis.call('HSET',KEYS[5],info.user..':'..info.key,token)
        end
        return 1
        """,Long.class);
    public void restore(AsyncSnapshot snapshot) {
        outside();var tokens=new ArrayList<Map<String,Object>>();
        for(var t:snapshot.tokens()) {
            var p=new LinkedHashMap<String,Object>();p.put("token",t.token());p.put("id",t.id());p.put("user",Long.toString(t.user()));p.put("key",t.key());p.put("keyHash",com.ticketflow.service.TradeExecutor.hash(t.key()));p.put("hash",t.hash());p.put("tier",Long.toString(t.tier()));p.put("at",t.at());p.put("state",t.state());p.put("order",t.order()==null?"":t.order());tokens.add(p);
        }
        Long result=redis.execute(RESTORE,keys(snapshot.session(),snapshot.epoch()),json.writeValueAsString(Map.of("epoch",snapshot.epoch(),"start",snapshot.start(),"finish",snapshot.finish(),"starts",snapshot.starts(),"version",snapshot.version(),"owner",snapshot.owner())),json.writeValueAsString(snapshot.free()),json.writeValueAsString(snapshot.sequence()),json.writeValueAsString(tokens));
        if(!Long.valueOf(1).equals(result))throw new IllegalStateException("Rebuild namespace already exists");
        verify(snapshot);
    }
    public void verify(AsyncSnapshot snapshot) {
        outside();var k=keys(snapshot.session(),snapshot.epoch());
        var free=redis.opsForHash().entries(k.get(1));var seq=redis.opsForHash().entries(k.get(6));
        if(free.size()!=snapshot.free().size() || seq.size()!=snapshot.sequence().size())throw new IllegalStateException("Snapshot tier mismatch");
        for(String tier:snapshot.free().keySet())if(!snapshot.free().get(tier).toString().equals(free.get(tier)) || !snapshot.sequence().get(tier).toString().equals(seq.get(tier)))throw new IllegalStateException("Snapshot balance mismatch");
        var tokens=redis.opsForHash().entries(k.get(2));var slots=redis.opsForHash().entries(k.get(3));var requests=redis.opsForHash().entries(k.get(4));
        if(tokens.size()!=snapshot.tokens().size() || slots.size()!=tokens.size() || requests.size()!=tokens.size())throw new IllegalStateException("Snapshot mapping count mismatch");
        for(var t:snapshot.tokens()) {
            Object raw=tokens.get(t.token());if(raw==null)throw new IllegalStateException("Snapshot token missing");var p=json.readTree(raw.toString());
            if(!p.path("complete").asBoolean() || !t.id().equals(p.path("id").asString()) || !t.state().equals(p.path("state").asString()) || !t.hash().equals(p.path("hash").asString())
                    || !Long.toString(t.user()).equals(p.path("user").asString()) || !Long.toString(t.tier()).equals(p.path("tier").asString()) || !(t.order()==null?"":t.order()).equals(p.path("order").asString())
                    || !t.token().equals(slots.get(Long.toString(t.user()))) || !t.token().equals(requests.get(t.user()+":"+com.ticketflow.service.TradeExecutor.hash(t.key()))))throw new IllegalStateException("Snapshot mapping mismatch");
        }
        var gate=redis.opsForHash().entries(k.get(0));
        if(!snapshot.owner().equals(gate.get("owner")) || !Long.toString(snapshot.version()).equals(gate.get("snapshot")) || !Long.toString(snapshot.epoch()).equals(gate.get("epoch")))throw new IllegalStateException("Snapshot owner mismatch");
    }

    /** Online observation; caller must compare DB watermarks before and after this read. */
    public List<String> differences(AsyncSnapshot snapshot) {
        outside();var result=new ArrayList<String>();var k=keys(snapshot.session(),snapshot.epoch());
        if(!healthy(snapshot.session(),snapshot.epoch(),snapshot.free().keySet()))result.add("REDIS_GATE_OR_TIER_MISSING");
        Long tentative=redis.opsForZSet().zCard(k.get(5));
        if(tentative!=null && tentative>0){result.add("TENTATIVE_PENDING:"+tentative);return result;}
        var free=redis.opsForHash().entries(k.get(1));var seq=redis.opsForHash().entries(k.get(6));
        for(String tier:snapshot.free().keySet()) {
            if(!snapshot.sequence().get(tier).toString().equals(seq.get(tier)))result.add("PROJECTION_LAG:"+tier);
            else if(!snapshot.free().get(tier).toString().equals(free.get(tier)))result.add("FREE_MISMATCH:"+tier);
        }
        if(result.stream().anyMatch(s->s.startsWith("PROJECTION_LAG")))return result;
        var tokens=redis.opsForHash().entries(k.get(2));var slots=redis.opsForHash().entries(k.get(3));
        long active=tokens.values().stream().map(v->json.readTree(v.toString())).filter(p->!"RELEASED".equals(p.path("state").asString())).count();
        if(active!=snapshot.tokens().size() || slots.size()!=snapshot.tokens().size())result.add("ACTIVE_MAPPING_COUNT");
        for(var t:snapshot.tokens()) {
            Object raw=tokens.get(t.token());
            if(raw==null){result.add("TOKEN_MISSING:"+t.id());continue;}
            var p=json.readTree(raw.toString());
            if(!t.id().equals(p.path("id").asString()) || !t.state().equals(p.path("state").asString()) || !Long.toString(t.tier()).equals(p.path("tier").asString()) || !Long.toString(t.user()).equals(p.path("user").asString()) || !t.token().equals(slots.get(Long.toString(t.user()))))result.add("TOKEN_MAPPING:"+t.id());
        }
        return result;
    }
}
