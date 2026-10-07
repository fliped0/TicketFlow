package com.ticketflow.service;
import com.ticketflow.mapper.*;
import com.ticketflow.model.entity.*;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class AsyncEvents {
    private final AsyncRequestMapper requests;private final OutboxMapper outbox;private final JsonMapper json;
    public AsyncEvents(AsyncRequestMapper requests,OutboxMapper outbox,JsonMapper json) {this.requests=requests;this.outbox=outbox;this.json=json;}
    public void broker(AsyncRequest r,LocalDateTime at) {
        String id=UUID.randomUUID().toString();String trace=MDC.get("traceId");
        var message=new AsyncMessage(1,id,r.id(),r.sessionId(),r.epoch(),at.toInstant(ZoneOffset.UTC).toString(),trace==null?"server":trace);
        outbox.insert(id,"BROKER","CREATE_ORDER",r.id(),r.sessionId(),r.tierId(),r.epoch(),null,json.writeValueAsString(message),at);
    }
    public void projection(AsyncRequest r,String type,Long order,LocalDateTime at) {
        if(r.token()==null)return;
        projection(UUID.randomUUID().toString(),r.id(),r.userId(),r.sessionId(),r.tierId(),r.epoch(),r.token(),type,order,at);
    }
    public void projection(String id,String request,long user,long session,long tier,long epoch,String token,String type,Long order,LocalDateTime at) {
        if(outbox.get(id)!=null)return;
        long seq=requests.sequence(tier);
        outbox.insert(id,"REDIS",type,request,session,tier,epoch,seq,json.writeValueAsString(Map.of("token",token,"userId",Long.toString(user),"requestId",request,"orderId",order==null?"":order.toString())),at);
    }
}
