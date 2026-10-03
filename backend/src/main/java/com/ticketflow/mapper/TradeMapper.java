package com.ticketflow.mapper;

import com.ticketflow.model.entity.TradeOperation;
import com.ticketflow.model.entity.TradeRequest;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TradeMapper {
    private final JdbcTemplate db;
    public TradeMapper(JdbcTemplate db) { this.db = db; }
    public LocalDateTime now() {
        return LocalDateTime.parse(db.queryForObject("SELECT DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f')", String.class));
    }
    public boolean lockUser(long id) {
        List<Boolean> rows = db.query("SELECT enabled FROM tf_user WHERE id=? FOR UPDATE", (r,n)->r.getBoolean(1), id);
        return !rows.isEmpty() && rows.get(0);
    }
    public boolean lockOwner(long id) {
        return !db.queryForList("SELECT id FROM tf_user WHERE id=? FOR UPDATE",Long.class,id).isEmpty();
    }
    public TradeRequest request(long user, TradeOperation operation, String key) {
        var rows = db.query("SELECT * FROM tf_request WHERE user_id=? AND operation=? AND request_key=? FOR UPDATE",
                (r,n)->new TradeRequest(r.getLong("id"), r.getString("payload_hash"), r.getString("state"),
                        r.getInt("http_status"), r.getString("result_code"), r.getString("result_json"),
                        r.getObject("order_id", Long.class)), user, operation.name(), key);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public long begin(long user, TradeOperation operation, String key, String hash) {
        var generated = new GeneratedKeyHolder();
        int count = db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO tf_request(user_id,operation,request_key,payload_hash,state,created_at) VALUES(?,?,?,?,'PROCESSING',UTC_TIMESTAMP(6))", new String[]{"id"});
            statement.setLong(1,user); statement.setString(2,operation.name()); statement.setString(3,key); statement.setString(4,hash);
            return statement;
        }, generated);
        requireOne(count);
        return generated.getKey().longValue();
    }
    public void complete(long id, String state, int httpStatus, String code, String result, Long orderId) {
        if (!List.of("SUCCEEDED","REJECTED").contains(state)) throw new IllegalArgumentException("Nonterminal request");
        requireOne(db.update("UPDATE tf_request SET state=?,http_status=?,result_code=?,result_json=?,order_id=?,completed_at=UTC_TIMESTAMP(6) WHERE id=? AND state='PROCESSING'",
                state,httpStatus,code,result,orderId,id));
    }
    public static void requireOne(int count) { if (count != 1) throw new IllegalStateException("Expected one affected row"); }
}
