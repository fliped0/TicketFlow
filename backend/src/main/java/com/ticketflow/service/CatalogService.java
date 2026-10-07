package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.mapper.CatalogMapper;
import com.ticketflow.mapper.CatalogMapper.EventRow;
import com.ticketflow.mapper.CatalogMapper.SessionRow;
import com.ticketflow.mapper.CatalogMapper.TierRow;
import com.ticketflow.model.dto.CatalogDTO;
import com.ticketflow.model.vo.CatalogVO;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Arrays;
import tools.jackson.core.type.TypeReference;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CatalogService {
    private final CatalogMapper db;
    private final JsonMapper json;
    private final CatalogCacheService cache;
    public CatalogService(CatalogMapper db, JsonMapper json, CatalogCacheService cache) { this.db = db; this.json = json; this.cache = cache; }

    private static BusinessException invalid() { return new BusinessException(400,"VALIDATION_ERROR","请求参数不合法"); }
    private static BusinessException missing() { return new BusinessException(404,"NOT_FOUND","资源不存在"); }
    private static BusinessException conflict(String code) { return new BusinessException(409,code,"目录状态冲突"); }
    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) throw invalid();
        return value;
    }
    private static String description(String value) { if (value == null || value.length() > 5000) throw invalid(); return value; }
    private static long price(Long value) { if (value == null || value < 1 || value > 100_000_000) throw invalid(); return value; }
    private static int capacity(Integer value) { if (value == null || value < 0 || value > 1_000_000) throw invalid(); return value; }
    private static long version(Long value) { if (value == null || value < 0) throw invalid(); return value; }
    private static void expected(long actual, Long supplied) { if (actual != version(supplied)) throw conflict("VERSION_CONFLICT"); }
    private static LocalDateTime utc(String text) {
        if (text == null) throw invalid();
        try { return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
        catch (DateTimeParseException e) { throw invalid(); }
    }
    private static String iso(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC).toString(); }
    private static void validTimes(LocalDateTime starts, LocalDateTime saleStart, LocalDateTime saleEnd, LocalDateTime now) {
        if (!saleStart.isAfter(now) || !saleStart.isBefore(saleEnd) || saleEnd.isAfter(starts)) throw invalid();
    }
    private static int page(Integer value) { if (value == null) return 1; if (value < 1) throw invalid(); return value; }
    private static int size(Integer value) { if (value == null) return 20; if (value < 1 || value > 100) throw invalid(); return value; }
    private static int offset(int page, int size) { long n = (long)(page - 1) * size; if (n > Integer.MAX_VALUE) throw invalid(); return (int)n; }
    private static String like(String keyword) {
        if (keyword == null) return null;
        if (keyword.length() > 100) throw invalid();
        return "%" + keyword.replace("!","!!").replace("%","!%").replace("_","!_") + "%";
    }
    private static String optionalFilter(String text, int max) { if (text != null && (text.isBlank() || text.length() > max)) throw invalid(); return text; }
    private EventRow requireEvent(long id, boolean lock) { EventRow row = db.event(id,lock); if (row == null) throw missing(); return row; }
    private SessionRow session(long id, boolean lock) { SessionRow row = db.session(id,lock); if (row == null) throw missing(); return row; }
    private TierRow tier(long id, boolean lock) { TierRow row = db.tier(id,lock); if (row == null) throw missing(); return row; }
    private void actor(long actor) { if (!db.lockActor(actor)) throw new BusinessException(403,"FORBIDDEN","无权访问"); }
    private void audit(long actor, String action, String type, long id, Object before, Object after) {
        String trace = MDC.get("traceId");
        db.audit(actor,action,type,id,before == null ? null : json.writeValueAsString(before),
                json.writeValueAsString(after),trace == null ? "server" : trace);
        db.advanceRevision(); // Atomic logical invalidation: visible only when this write commits.
    }
    private static CatalogVO.Event view(EventRow row) { return new CatalogVO.Event(Long.toString(row.id()),row.name(),row.description(),row.category(),row.city(),row.venue(),row.status(),row.version()); }
    private String saleStatus(EventRow event, SessionRow session, int available, LocalDateTime now) {
        if (!event.status().equals("ON_SALE")) return "NOT_ON_SALE";
        if (now.isBefore(session.saleStartAt())) return "SALE_NOT_STARTED";
        if (!now.isBefore(session.saleEndAt())) return "SALE_ENDED";
        return available == 0 ? "SOLD_OUT" : "ON_SALE";
    }
    private CatalogVO.Session view(SessionRow row, EventRow event, LocalDateTime now) {
        return new CatalogVO.Session(Long.toString(row.id()),Long.toString(row.eventId()),iso(row.startsAt()),
                iso(row.saleStartAt()),iso(row.saleEndAt()),saleStatus(event,row,db.availableInSession(row.id()),now),row.version());
    }
    private CatalogVO.Tier view(TierRow row, SessionRow session, EventRow event, LocalDateTime now) {
        return new CatalogVO.Tier(Long.toString(row.id()),Long.toString(row.sessionId()),row.name(),row.priceFen(),
                row.available(),row.capacity(),saleStatus(event,session,row.available(),now),row.refundPolicy(),row.version());
    }

    @Transactional
    public CatalogVO.Event createEvent(long actor, CatalogDTO.Event input) {
        if (input == null) throw invalid(); actor(actor);
        long id = db.createEvent(required(input.name(),100), description(input.description()),
                required(input.category(),32),required(input.city(),64),required(input.venue(),255));
        CatalogVO.Event result = view(requireEvent(id,false)); audit(actor,"CREATE","EVENT",id,null,result); return result;
    }
    @Transactional
    public CatalogVO.Event updateEvent(long actor, long id, CatalogDTO.EventUpdate input) {
        if (input == null) throw invalid(); actor(actor); EventRow before = requireEvent(id,true); expected(before.version(),input.expectedVersion());
        String name=required(input.name(),100), desc=description(input.description()), category=required(input.category(),32), city=required(input.city(),64), venue=required(input.venue(),255);
        if (!before.city().equals(city) || !before.venue().equals(venue)) {
            for (SessionRow row : db.sessions(id,true)) if (!db.now().isBefore(row.freezeAt())) throw conflict("CONFIG_FROZEN");
        }
        db.updateEvent(id,name,desc,category,city,venue);
        CatalogVO.Event result=view(requireEvent(id,false)); audit(actor,"UPDATE","EVENT",id,view(before),result); return result;
    }
    @Transactional
    public CatalogVO.Event setStatus(long actor, long id, CatalogDTO.Status input) {
        if (input == null || !List.of("ON_SALE","OFF_SALE").contains(input.status())) throw invalid();
        version(input.expectedVersion());
        actor(actor); EventRow before=requireEvent(id,true);
        if (before.status().equals(input.status())) return view(before);
        expected(before.version(),input.expectedVersion());
        if (input.status().equals("ON_SALE")) {
            List<SessionRow> sessions=db.sessions(id,true);
            if (sessions.isEmpty()) throw conflict("INCOMPLETE_CATALOG");
            for (SessionRow row:sessions) {
                List<TierRow> tiers=db.tiers(row.id(),true);
                if (tiers.isEmpty() || tiers.size()!=db.tierCount(row.id()) || !row.saleStartAt().isBefore(row.saleEndAt()) || row.saleEndAt().isAfter(row.startsAt())) throw conflict("INCOMPLETE_CATALOG");
            }
        }
        db.setStatus(id,input.status()); CatalogVO.Event result=view(requireEvent(id,false));
        audit(actor,"STATUS","EVENT",id,view(before),result); return result;
    }
    @Transactional
    public CatalogVO.Session createSession(long actor,long eventId,CatalogDTO.Session input) {
        if (input == null) throw invalid(); actor(actor); EventRow parent=requireEvent(eventId,true);
        if (parent.status().equals("ON_SALE")) throw conflict("CONFIG_FROZEN");
        LocalDateTime starts=utc(input.startsAt()), saleStart=utc(input.saleStartAt()), saleEnd=utc(input.saleEndAt()), now=db.now();
        validTimes(starts,saleStart,saleEnd,now);
        long id=db.createSession(eventId,starts,saleStart,saleEnd);
        CatalogVO.Session result=view(session(id,false),parent,now); audit(actor,"CREATE","SESSION",id,null,result); return result;
    }
    @Transactional
    public CatalogVO.Session updateSession(long actor,long id,CatalogDTO.SessionUpdate input) {
        if (input == null) throw invalid(); actor(actor);
        SessionRow route=session(id,false); EventRow parent=requireEvent(route.eventId(),true); SessionRow before=session(id,true);
        expected(before.version(),input.expectedVersion());
        LocalDateTime now=db.now(); if (!now.isBefore(before.freezeAt())) throw conflict("CONFIG_FROZEN");
        LocalDateTime starts=utc(input.startsAt()), saleStart=utc(input.saleStartAt()), saleEnd=utc(input.saleEndAt());
        validTimes(starts,saleStart,saleEnd,now);
        LocalDateTime freeze=before.freezeAt().isBefore(saleStart)?before.freezeAt():saleStart;
        db.updateSession(id,starts,saleStart,saleEnd,freeze);
        CatalogVO.Session result=view(session(id,false),parent,now); audit(actor,"UPDATE","SESSION",id,view(before,parent,now),result); return result;
    }
    @Transactional
    public CatalogVO.Tier createTier(long actor,long sessionId,CatalogDTO.Tier input) {
        if (input == null) throw invalid(); actor(actor);
        SessionRow route=session(sessionId,false); EventRow parent=requireEvent(route.eventId(),true); SessionRow session=session(sessionId,true);
        LocalDateTime now=db.now(); if (!now.isBefore(session.freezeAt())) throw conflict("CONFIG_FROZEN");
        String name=required(input.name(),100); long price=price(input.priceFen()); int capacity=capacity(input.capacity());
        if (db.tierNameExists(sessionId,name,-1)) throw conflict("TIER_NAME_EXISTS");
        try {
            long id=db.createTier(sessionId,name,price,capacity);
            CatalogVO.Tier result=view(tier(id,false),session,parent,now); audit(actor,"CREATE","TIER",id,null,result); return result;
        } catch (DuplicateKeyException e) { throw conflict("TIER_NAME_EXISTS"); }
    }
    @Transactional
    public CatalogVO.Tier updateTier(long actor,long id,CatalogDTO.TierUpdate input) {
        if (input == null) throw invalid(); actor(actor);
        TierRow route=tier(id,false); SessionRow routeSession=session(route.sessionId(),false);
        EventRow parent=requireEvent(routeSession.eventId(),true); SessionRow session=session(route.sessionId(),true); TierRow before=tier(id,true);
        expected(before.version(),input.expectedVersion()); LocalDateTime now=db.now();
        if (!now.isBefore(session.freezeAt())) throw conflict("CONFIG_FROZEN");
        String name=required(input.name(),100); long price=price(input.priceFen()); int capacity=capacity(input.capacity());
        if (db.tierNameExists(session.id(),name,id)) throw conflict("TIER_NAME_EXISTS");
        if (capacity!=before.capacity() && (before.reserved()!=0 || before.sold()!=0 || db.orderCount(id)!=0)) throw conflict("CONFIG_FROZEN");
        try {
            db.updateTier(id,name,price,capacity,capacity!=before.capacity());
            CatalogVO.Tier result=view(tier(id,false),session,parent,now); audit(actor,"UPDATE","TIER",id,view(before,session,parent,now),result); return result;
        } catch (DuplicateKeyException e) { throw conflict("TIER_NAME_EXISTS"); }
    }

    public CatalogVO.Page<CatalogVO.Event> events(boolean admin,String status,String keyword,String city,String category,Integer p,Integer s) {
        int page=page(p),size=size(s),offset=offset(page,size);
        if (!admin) status="ON_SALE";
        else if (status!=null && !List.of("DRAFT","ON_SALE","OFF_SALE").contains(status)) throw invalid();
        String pattern=like(keyword); city=optionalFilter(city,64); category=optionalFilter(category,32);
        String filterStatus=status, filterCity=city, filterCategory=category;
        java.util.function.Supplier<CatalogVO.Page<CatalogVO.Event>> loader=()->new CatalogVO.Page<>(
                db.events(filterStatus,pattern,filterCity,filterCategory,size,offset).stream().map(CatalogService::view).toList(),
                page,size,db.countEvents(filterStatus,pattern,filterCity,filterCategory));
        if (admin) return cache.snapshot(loader);
        return cache.query(()->cache.get("events",Arrays.asList(pattern,filterCity,filterCategory,page,size),
                new TypeReference<CatalogVO.Page<CatalogVO.Event>>() {},loader,value->value.items().isEmpty()));
    }
    public CatalogVO.Event event(long id,boolean admin) {
        if (admin) return cache.snapshot(()->view(requireEvent(id,false)));
        return cache.query(()->{
            var result=cache.get("event",List.of(id),new TypeReference<CatalogVO.Event>() {},()->{
                EventRow row=db.event(id,false); return row==null || !row.status().equals("ON_SALE") ? null : view(row);
            },value->value==null);
            if (result==null) throw missing();
            return result;
        });
    }
    public CatalogVO.Page<CatalogVO.Session> sessions(long eventId,boolean admin,Integer p,Integer s) {
        int page=page(p),size=size(s),offset=offset(page,size);
        if (admin) return cache.snapshot(()->{
            EventRow parent=requireEvent(eventId,false); LocalDateTime now=db.now();
            return new CatalogVO.Page<>(db.pageSessions(eventId,size,offset).stream().map(row->view(row,parent,now)).toList(),page,size,db.countSessions(eventId));
        });
        return cache.query(()->{
            var cached=cache.get("sessions",List.of(eventId,page,size),new TypeReference<CatalogVO.Page<CatalogVO.Session>>() {},()->{
                EventRow parent=requireEvent(eventId,false); if (!parent.status().equals("ON_SALE")) throw missing();
                return new CatalogVO.Page<>(db.pageSessions(eventId,size,offset).stream().map(row->new CatalogVO.Session(
                        Long.toString(row.id()),Long.toString(row.eventId()),iso(row.startsAt()),iso(row.saleStartAt()),iso(row.saleEndAt()),"",row.version())).toList(),
                        page,size,db.countSessions(eventId));
            },value->value.items().isEmpty());
            EventRow parent=requireEvent(eventId,false); if (!parent.status().equals("ON_SALE")) throw missing();
            LocalDateTime now=db.now();
            var available=db.availableForSessions(cached.items().stream().map(row->Long.parseLong(row.id())).toList());
            return new CatalogVO.Page<>(cached.items().stream().map(row->new CatalogVO.Session(row.id(),row.eventId(),row.startsAt(),
                    row.saleStartAt(),row.saleEndAt(),saleStatus(parent,new SessionRow(Long.parseLong(row.id()),eventId,utc(row.startsAt()),
                    utc(row.saleStartAt()),utc(row.saleEndAt()),utc(row.saleStartAt()),row.version()),available.getOrDefault(Long.parseLong(row.id()),0),now),row.version())).toList(),page,size,cached.total());
        });
    }
    public CatalogVO.Page<CatalogVO.Tier> tiers(long sessionId,boolean admin,Integer p,Integer s) {
        int page=page(p),size=size(s),offset=offset(page,size);
        if (admin) return cache.snapshot(()->{
            SessionRow session=session(sessionId,false); EventRow parent=requireEvent(session.eventId(),false); LocalDateTime now=db.now();
            return new CatalogVO.Page<>(db.pageTiers(sessionId,size,offset).stream().map(row->view(row,session,parent,now)).toList(),page,size,db.countTiers(sessionId));
        });
        return cache.query(()->{
            var cached=cache.get("tiers",List.of(sessionId,page,size),new TypeReference<CatalogVO.Page<CatalogVO.Tier>>() {},()->{
                SessionRow session=session(sessionId,false); EventRow parent=requireEvent(session.eventId(),false);
                if (!parent.status().equals("ON_SALE")) throw missing();
                return new CatalogVO.Page<>(db.pageTiers(sessionId,size,offset).stream().map(row->new CatalogVO.Tier(Long.toString(row.id()),
                        Long.toString(row.sessionId()),row.name(),row.priceFen(),0,row.capacity(),"",row.refundPolicy(),row.version())).toList(),page,size,db.countTiers(sessionId));
            },value->value.items().isEmpty());
            SessionRow session=session(sessionId,false); EventRow parent=requireEvent(session.eventId(),false);
            if (!parent.status().equals("ON_SALE")) throw missing(); LocalDateTime now=db.now();
            var available=db.availableForTiers(cached.items().stream().map(row->Long.parseLong(row.id())).toList());
            return new CatalogVO.Page<>(cached.items().stream().map(row->{
                int free=available.getOrDefault(Long.parseLong(row.id()),0);
                return new CatalogVO.Tier(row.id(),row.sessionId(),row.name(),row.priceFen(),free,row.capacity(),saleStatus(parent,session,free,now),row.refundPolicy(),row.version());
            }).toList(),page,size,cached.total());
        });
    }
}
