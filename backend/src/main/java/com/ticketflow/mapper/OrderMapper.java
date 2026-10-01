package com.ticketflow.mapper;

import com.ticketflow.model.entity.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class OrderMapper {
    private final JdbcTemplate db;
    public OrderMapper(JdbcTemplate db) { this.db = db; }
    private static LocalDateTime at(ResultSet r, String key) throws SQLException { return LocalDateTime.parse(r.getString(key).replace(' ','T')); }
    private static String ts(LocalDateTime t) { return t.toString().replace('T',' '); }
    private static final RowMapper<OrderRecord> ORDER = (r,n)->new OrderRecord(r.getLong("id"),r.getLong("user_id"),
            r.getLong("session_id"),r.getLong("tier_id"),OrderStatus.valueOf(r.getString("status")),r.getInt("quantity"),
            r.getLong("unit_price_fen"),r.getLong("amount_fen"),r.getString("snapshot"),at(r,"created_at"),at(r,"expires_at"));

    public PurchaseCatalog lockCatalog(long tierId) {
        // Route reads never lock children ahead of their parents. Ownership is immutable.
        var route = db.query("SELECT t.session_id,s.event_id FROM tf_tier t JOIN tf_session s ON s.id=t.session_id WHERE t.id=?",
                (r,n)->new long[]{r.getLong(1),r.getLong(2)},tierId);
        if (route.isEmpty()) return null;
        long session = route.get(0)[0], event = route.get(0)[1];
        var events = db.queryForList("SELECT id FROM tf_event WHERE id=? FOR SHARE",Long.class,event);
        var sessions = db.queryForList("SELECT id FROM tf_session WHERE id=? AND event_id=? FOR SHARE",Long.class,session,event);
        var tiers = db.queryForList("SELECT id FROM tf_tier WHERE id=? AND session_id=? FOR SHARE",Long.class,tierId,session);
        if (events.isEmpty() || sessions.isEmpty() || tiers.isEmpty()) return null;
        return db.queryForObject("SELECT e.id event_id,s.id session_id,t.id tier_id,e.name event_name,e.city,e.venue,e.status,s.starts_at,s.sale_start_at,s.sale_end_at,t.name tier_name,t.price_fen,t.refund_policy FROM tf_event e JOIN tf_session s ON s.event_id=e.id JOIN tf_tier t ON t.session_id=s.id WHERE t.id=?",
                (r,n)->new PurchaseCatalog(r.getLong("event_id"),r.getLong("session_id"),r.getLong("tier_id"),r.getString("event_name"),
                        r.getString("city"),r.getString("venue"),r.getString("status"),at(r,"starts_at"),at(r,"sale_start_at"),at(r,"sale_end_at"),
                        r.getString("tier_name"),r.getLong("price_fen"),r.getString("refund_policy")),tierId);
    }
    public boolean hasSlot(long user, long session) { return !db.queryForList("SELECT order_id FROM tf_purchase_slot WHERE user_id=? AND session_id=? FOR UPDATE",Long.class,user,session).isEmpty(); }
    public long insert(long user, PurchaseCatalog catalog, String snapshot, LocalDateTime now, LocalDateTime expiry) {
        var key = new GeneratedKeyHolder();
        Object[] values = {user,catalog.sessionId(),catalog.tierId(),catalog.priceFen(),catalog.priceFen(),snapshot,ts(catalog.startsAt()),ts(expiry),ts(now),ts(now)};
        TradeMapper.requireOne(db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO tf_order(user_id,session_id,tier_id,status,quantity,unit_price_fen,amount_fen,snapshot,starts_at,expires_at,created_at,updated_at) VALUES(?,?,?,'PENDING',1,?,?,?,?,?,?,?)",new String[]{"id"});
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },key));
        return key.getKey().longValue();
    }
    public void insertSlot(long user, long session, long order) { TradeMapper.requireOne(db.update("INSERT INTO tf_purchase_slot(user_id,session_id,order_id) VALUES(?,?,?)",user,session,order)); }
    public OrderRecord owned(long user, long id) {
        var rows=db.query("SELECT * FROM tf_order WHERE user_id=? AND id=?",ORDER,user,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<OrderRecord> list(long user, String status, int size, int offset) { return db.query("SELECT * FROM tf_order WHERE user_id=? AND (? IS NULL OR status=?) ORDER BY id DESC LIMIT ? OFFSET ?",ORDER,user,status,status,size,offset); }
    public long count(long user, String status) { return db.queryForObject("SELECT COUNT(*) FROM tf_order WHERE user_id=? AND (? IS NULL OR status=?)",Long.class,user,status,status); }
}
