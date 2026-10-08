package com.ticketflow.mapper;
import com.ticketflow.model.entity.AsyncGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class AsyncGateMapper {
    private final JdbcTemplate db;
    public AsyncGateMapper(JdbcTemplate db) { this.db=db; }
    public AsyncGate read(long session) {return gate(session,null);}
    public AsyncGate lock(long session,boolean exclusive) {return gate(session,exclusive?" FOR UPDATE":" FOR SHARE");}
    private AsyncGate gate(long session,String suffix) {
        var rows=db.query("SELECT session_id,epoch,phase FROM tf_async_gate WHERE session_id=?"+(suffix==null?"":suffix),
                (r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getString(3)},session);
        if(rows.isEmpty())return null;
        String mode=db.queryForObject("SELECT purchase_mode FROM tf_session WHERE id=?",String.class,session);
        return new AsyncGate(session,(long)rows.get(0)[1],(String)rows.get(0)[2],mode);
    }
    public void pause(long session) {TradeMapper.requireOne(db.update("UPDATE tf_async_gate SET phase='PAUSED',updated_at=UTC_TIMESTAMP(6) WHERE session_id=?",session));}
    public void changed(long session) {TradeMapper.requireOne(db.update("UPDATE tf_async_gate SET phase='PAUSED',epoch=epoch+1,maintenance_version=maintenance_version+1,maintenance_owner=NULL,updated_at=UTC_TIMESTAMP(6) WHERE session_id=?",session));}
    public void ready(long session,long epoch) {TradeMapper.requireOne(db.update("UPDATE tf_async_gate SET phase='READY',updated_at=UTC_TIMESTAMP(6) WHERE session_id=? AND epoch=? AND phase='PAUSED'",session,epoch));}
}
