package com.ticketflow.model.entity;
import java.util.UUID;
import java.time.Instant;
public record AsyncMessage(int schemaVersion,String eventId,String requestId,long sessionId,long epoch,String createdAt,String traceId) {
    public AsyncMessage {
        if(schemaVersion!=1 || sessionId<=0 || epoch<=0 || traceId==null || traceId.length()>64)throw new IllegalArgumentException("Invalid message");
        UUID.fromString(eventId);UUID.fromString(requestId);Instant.parse(createdAt);
    }
}
