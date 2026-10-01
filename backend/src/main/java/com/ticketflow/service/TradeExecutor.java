package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.TradeMapper;
import com.ticketflow.model.entity.TradeOperation;
import com.ticketflow.model.vo.TradeOutcome;
import com.ticketflow.model.vo.TradeResultVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TradeExecutor {
    private static final long BUDGET_NANOS = 5_000_000_000L;
    private final PlatformTransactionManager manager;
    private final TradeMapper db;
    private final OrderMapper orders;
    private final JsonMapper json;
    public TradeExecutor(PlatformTransactionManager manager, TradeMapper db, OrderMapper orders, JsonMapper json) {
        this.manager=manager; this.db=db; this.orders=orders; this.json=json;
    }
    public static String hash(String canonical) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void validateKey(String key) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{16,64}")) throw new BusinessException(400,"VALIDATION_ERROR","请求键不合法");
    }
    public TradeOutcome execute(long user, TradeOperation operation, String key, String payloadHash, int successStatus,
                                Supplier<TradeResultVO> action) {
        validateKey(key);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Trade must own its transaction");
        long deadline=System.nanoTime()+BUDGET_NANOS;
        for (int attempt=0;;attempt++) {
            var tx=new TransactionTemplate(manager);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout((int)Math.max(1,(deadline-System.nanoTime()+999_999_999L)/1_000_000_000L));
            try {
                return tx.execute(status -> {
                    if (!db.lockUser(user)) throw new BusinessException(401,"UNAUTHENTICATED","请重新登录");
                    var previous=db.request(user,operation,key);
                    if (previous!=null) {
                        if (!previous.payloadHash().equals(payloadHash)) throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","请求键已用于不同参数");
                        if (!previous.state().equals("SUCCEEDED") && !previous.state().equals("REJECTED")) throw new IllegalStateException("Committed nonterminal request");
                        var stored=json.readValue(previous.resultJson(),TradeOutcome.class);
                        TradeResultVO data=stored.data();
                        if (previous.orderId()!=null && data!=null) {
                            var order=orders.owned(user,previous.orderId());
                            if (order==null) throw new IllegalStateException("Missing replay order");
                            data=new TradeResultVO(data.orderId(),data.operationStatus(),order.status().name(),data.amountFen(),data.expiresAt());
                        }
                        return new TradeOutcome(previous.httpStatus(),previous.resultCode(),stored.message(),data,true);
                    }
                    long requestId=db.begin(user,operation,key,payloadHash);
                    Object savepoint=status.createSavepoint();
                    TradeOutcome result;
                    String state;
                    Long orderId=null;
                    try {
                        var data=action.get();
                        if (data==null) throw new IllegalStateException("Missing trade result");
                        orderId=Long.parseLong(data.orderId());
                        result=new TradeOutcome(successStatus,"OK","成功",data,false);
                        state="SUCCEEDED";
                    } catch (BusinessRejection rejection) {
                        status.rollbackToSavepoint(savepoint);
                        result=new TradeOutcome(rejection.status(),rejection.code(),rejection.getMessage(),null,false);
                        state="REJECTED";
                    }
                    status.releaseSavepoint(savepoint);
                    db.complete(requestId,state,result.httpStatus(),result.code(),json.writeValueAsString(result),orderId);
                    return result;
                }); // Response leaves the executor only after commit succeeds.
            } catch (DataAccessException error) {
                if (!retryableLockFailure(error) || attempt>=2 || deadline-System.nanoTime()<200_000_000L) throw error;
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(20,101)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error; }
            }
        }
    }
    private static boolean retryableLockFailure(Throwable error) {
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode()==1205 || sql.getErrorCode()==1213)) return true;
        }
        return false;
    }
}
