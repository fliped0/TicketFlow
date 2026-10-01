package com.ticketflow.model.entity;

import java.time.LocalDateTime;

public record OrderRecord(long id, long userId, long sessionId, long tierId, OrderStatus status,
                          int quantity, long unitPriceFen, long amountFen, String snapshot,
                          LocalDateTime createdAt, LocalDateTime expiresAt) {}
