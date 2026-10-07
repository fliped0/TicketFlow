package com.ticketflow.service;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
@Service
public class AsyncTransactions {
    private final PlatformTransactionManager manager;
    public AsyncTransactions(PlatformTransactionManager manager) {this.manager=manager;}
    public <T>T execute(Supplier<T> action) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Async operation must own transaction");
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
        return tx.execute(status->action.get());
    }
    public <T>T snapshot(Supplier<T> action) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Query must own transaction");
        var tx=new TransactionTemplate(manager);tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(5);
        return tx.execute(status->action.get());
    }
}
