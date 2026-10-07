package com.ticketflow.mapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogMapper {
    public record EventRow(long id, String name, String description, String category, String city,
                           String venue, String status, long version) {}
    public record SessionRow(long id, long eventId, LocalDateTime startsAt, LocalDateTime saleStartAt,
                             LocalDateTime saleEndAt, LocalDateTime freezeAt, long version) {}
    public record TierRow(long id, long sessionId, String name, long priceFen, int capacity,
                          int available, int reserved, int sold, String refundPolicy, long version) {}

    private static final RowMapper<EventRow> EVENT = (r, n) -> new EventRow(r.getLong("id"), r.getString("name"),
            r.getString("description"), r.getString("category"), r.getString("city"),
            r.getString("venue"), r.getString("status"), r.getLong("version"));
    private static final RowMapper<SessionRow> SESSION = (r, n) -> new SessionRow(r.getLong("id"),
            r.getLong("event_id"), at(r, "starts_at"), at(r, "sale_start_at"), at(r, "sale_end_at"),
            at(r, "freeze_at"), r.getLong("version"));
    private static final RowMapper<TierRow> TIER = (r, n) -> new TierRow(r.getLong("id"),
            r.getLong("session_id"), r.getString("name"), r.getLong("price_fen"),
            r.getInt("capacity"), r.getInt("available"), r.getInt("reserved"), r.getInt("sold"),
            r.getString("refund_policy"), r.getLong("version"));
    private final JdbcTemplate db;

    public CatalogMapper(JdbcTemplate db) { this.db = db; }
    public long revision() { return db.queryForObject("SELECT revision FROM tf_catalog_revision WHERE id=1", Long.class); }
    public void advanceRevision() { TradeMapper.requireOne(db.update("UPDATE tf_catalog_revision SET revision=revision+1 WHERE id=1")); }
    public Map<Long,Integer> availableForTiers(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long,Integer> result = new HashMap<>();
        db.query("SELECT tier_id,available FROM tf_stock WHERE tier_id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(row.getLong(1),row.getInt(2)), ids.toArray());
        return result;
    }
    public Map<Long,Integer> availableForSessions(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long,Integer> result = new HashMap<>();
        db.query("SELECT t.session_id,COALESCE(SUM(s.available),0) FROM tf_tier t JOIN tf_stock s ON s.tier_id=t.id WHERE t.session_id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ") GROUP BY t.session_id",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(row.getLong(1),row.getInt(2)), ids.toArray());
        return result;
    }
    private static LocalDateTime at(ResultSet r, String key) throws SQLException { return LocalDateTime.parse(r.getString(key).replace(' ','T')); }
    private static String ts(LocalDateTime value) { return value.toString().replace('T',' '); }
    private static <T> T one(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }
    private long inserted(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public LocalDateTime now() { return LocalDateTime.parse(db.queryForObject("SELECT DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f')", String.class)); }
    public boolean lockActor(long id) { return !db.queryForList("SELECT id FROM tf_user WHERE id=? AND enabled=TRUE AND role='ADMIN' FOR UPDATE", Long.class, id).isEmpty(); }
    public EventRow event(long id, boolean lock) { return one(db.query("SELECT * FROM tf_event WHERE id=?" + (lock ? " FOR UPDATE" : ""), EVENT, id)); }
    public SessionRow session(long id, boolean lock) { return one(db.query("SELECT * FROM tf_session WHERE id=?" + (lock ? " FOR UPDATE" : ""), SESSION, id)); }
    public TierRow tier(long id, boolean lock) { return one(db.query("SELECT t.*, s.capacity, s.available, s.reserved, s.sold FROM tf_tier t JOIN tf_stock s ON s.tier_id=t.id WHERE t.id=?" + (lock ? " FOR UPDATE" : ""), TIER, id)); }
    public List<SessionRow> sessions(long eventId, boolean lock) { return db.query("SELECT * FROM tf_session WHERE event_id=? ORDER BY id" + (lock ? " FOR UPDATE" : ""), SESSION, eventId); }
    public List<TierRow> tiers(long sessionId, boolean lock) { return db.query("SELECT t.*, s.capacity, s.available, s.reserved, s.sold FROM tf_tier t JOIN tf_stock s ON s.tier_id=t.id WHERE t.session_id=? ORDER BY t.id" + (lock ? " FOR UPDATE" : ""), TIER, sessionId); }
    public int tierCount(long sessionId) { return db.queryForObject("SELECT COUNT(*) FROM tf_tier WHERE session_id=?", Integer.class, sessionId); }
    public int orderCount(long tierId) { return db.queryForObject("SELECT COUNT(*) FROM tf_order WHERE tier_id=?", Integer.class, tierId); }
    public boolean tierNameExists(long sessionId, String name, long exceptId) { return db.queryForObject("SELECT COUNT(*) FROM tf_tier WHERE session_id=? AND name=? AND id<>?", Integer.class, sessionId, name, exceptId) > 0; }

    public long createEvent(String name, String description, String category, String city, String venue) {
        return inserted("INSERT INTO tf_event(name,description,category,city,venue,status,version,created_at,updated_at) VALUES(?,?,?,?,?,'DRAFT',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", name, description, category, city, venue);
    }
    public void updateEvent(long id, String name, String description, String category, String city, String venue) {
        db.update("UPDATE tf_event SET name=?,description=?,category=?,city=?,venue=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?", name, description, category, city, venue, id);
    }
    public void setStatus(long id, String status) { db.update("UPDATE tf_event SET status=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?", status, id); }
    public long createSession(long eventId, LocalDateTime start, LocalDateTime saleStart, LocalDateTime saleEnd) {
        long id=inserted("INSERT INTO tf_session(event_id,starts_at,sale_start_at,sale_end_at,freeze_at,version,created_at,updated_at) VALUES(?,?,?,?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", eventId, ts(start), ts(saleStart), ts(saleEnd), ts(saleStart));
        db.update("INSERT INTO tf_async_gate(session_id,updated_at) VALUES(?,UTC_TIMESTAMP(6))",id);return id;
    }
    public void setPurchaseMode(long id,String mode) {TradeMapper.requireOne(db.update("UPDATE tf_session SET purchase_mode=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?",mode,id));}
    public void updateSession(long id, LocalDateTime start, LocalDateTime saleStart, LocalDateTime saleEnd, LocalDateTime freezeAt) {
        db.update("UPDATE tf_session SET starts_at=?,sale_start_at=?,sale_end_at=?,freeze_at=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?", ts(start), ts(saleStart), ts(saleEnd), ts(freezeAt), id);
    }
    public long createTier(long sessionId, String name, long priceFen, int capacity) {
        long id = inserted("INSERT INTO tf_tier(session_id,name,price_fen,version,created_at,updated_at) VALUES(?,?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", sessionId, name, priceFen);
        db.update("INSERT INTO tf_stock(tier_id,capacity,available,reserved,sold,updated_at) VALUES(?,?,?,0,0,UTC_TIMESTAMP(6))", id, capacity, capacity);
        db.update("INSERT INTO tf_async_tier_balance(tier_id) VALUES(?)",id);
        return id;
    }
    public void updateTier(long id, String name, long priceFen, int capacity, boolean capacityChanged) {
        db.update("UPDATE tf_tier SET name=?,price_fen=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?", name, priceFen, id);
        if (capacityChanged && db.update("UPDATE tf_stock SET capacity=?,available=?,updated_at=UTC_TIMESTAMP(6) WHERE tier_id=? AND reserved=0 AND sold=0", capacity, capacity, id) != 1)
            throw new IllegalStateException("Stock changed during catalog update");
    }
    public void audit(long actor, String action, String type, long objectId, String before, String after, String traceId) {
        db.update("INSERT INTO tf_audit(actor_id,action,object_type,object_id,before_json,after_json,trace_id,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6))", actor, action, type, objectId, before, after, traceId);
    }
    public long countEvents(String status, String keyword, String city, String category) {
        return db.queryForObject("SELECT COUNT(*) FROM tf_event WHERE (? IS NULL OR status=?) AND (? IS NULL OR name LIKE ? ESCAPE '!') AND (? IS NULL OR city=?) AND (? IS NULL OR category=?)", Long.class, status,status,keyword,keyword,city,city,category,category);
    }
    public List<EventRow> events(String status, String keyword, String city, String category, int limit, int offset) {
        return db.query("SELECT * FROM tf_event WHERE (? IS NULL OR status=?) AND (? IS NULL OR name LIKE ? ESCAPE '!') AND (? IS NULL OR city=?) AND (? IS NULL OR category=?) ORDER BY id DESC LIMIT ? OFFSET ?", EVENT, status,status,keyword,keyword,city,city,category,category,limit,offset);
    }
    public long countSessions(long eventId) { return db.queryForObject("SELECT COUNT(*) FROM tf_session WHERE event_id=?", Long.class, eventId); }
    public List<SessionRow> pageSessions(long eventId, int limit, int offset) { return db.query("SELECT * FROM tf_session WHERE event_id=? ORDER BY id LIMIT ? OFFSET ?", SESSION, eventId,limit,offset); }
    public long countTiers(long sessionId) { return db.queryForObject("SELECT COUNT(*) FROM tf_tier WHERE session_id=?", Long.class, sessionId); }
    public List<TierRow> pageTiers(long sessionId, int limit, int offset) { return db.query("SELECT t.*, s.capacity, s.available, s.reserved, s.sold FROM tf_tier t JOIN tf_stock s ON s.tier_id=t.id WHERE t.session_id=? ORDER BY t.id LIMIT ? OFFSET ?", TIER, sessionId,limit,offset); }
    public int availableInSession(long sessionId) { return db.queryForObject("SELECT COALESCE(SUM(s.available),0) FROM tf_tier t JOIN tf_stock s ON s.tier_id=t.id WHERE t.session_id=?", Integer.class, sessionId); }
}
