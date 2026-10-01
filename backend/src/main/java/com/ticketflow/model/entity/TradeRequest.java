package com.ticketflow.model.entity;

public record TradeRequest(long id, String payloadHash, String state, int httpStatus,
                           String resultCode, String resultJson, Long orderId) {}
