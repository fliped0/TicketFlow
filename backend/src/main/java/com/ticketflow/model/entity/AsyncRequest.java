package com.ticketflow.model.entity;
import java.time.LocalDateTime;
public record AsyncRequest(String id,long userId,long sessionId,long tierId,String key,String hash,String token,long epoch,
        String state,LocalDateTime acceptedAt,LocalDateTime deadline,Long orderId,String code,Integer httpStatus,
        long workVersion,String owner,LocalDateTime leaseUntil,int attempts,LocalDateTime nextRetry) {
    public boolean terminal() { return "SUCCEEDED".equals(state) || "REJECTED".equals(state); }
}
