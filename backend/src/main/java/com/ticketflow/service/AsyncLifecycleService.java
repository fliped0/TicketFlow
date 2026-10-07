package com.ticketflow.service;
import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.mapper.*;
import com.ticketflow.model.entity.OrderRecord;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
@Service
public class AsyncLifecycleService {
    private final AsyncGateMapper gates;private final AsyncRequestMapper requests;private final AsyncEvents events;
    public AsyncLifecycleService(AsyncGateMapper gates,AsyncRequestMapper requests,AsyncEvents events) {this.gates=gates;this.requests=requests;this.events=events;}
    public void check(long session) {var gate=gates.read(session);if("ASYNC".equals(gate.mode()) && !"READY".equals(gate.phase()))throw new BusinessException(503,"ASYNC_PAUSED","场次暂不可用");}
    public void beforeOrder(OrderRecord order) {var r=requests.forOrder(order.id());if(r!=null && !r.terminal())throw new IllegalStateException("Nonterminal async order");}
    public void afterStock(OrderRecord order) {if("ASYNC".equals(gates.read(order.sessionId()).mode()))requests.lockBalance(order.tierId());}
    public void released(OrderRecord order,LocalDateTime now) {var r=requests.forOrder(order.id());if(r!=null)events.projection(r,"RELEASE",order.id(),now);}
}
