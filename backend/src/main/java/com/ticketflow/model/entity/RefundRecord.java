package com.ticketflow.model.entity;

import java.time.LocalDateTime;

public record RefundRecord(long id, long orderId, long amountFen, LocalDateTime refundedAt) {}
