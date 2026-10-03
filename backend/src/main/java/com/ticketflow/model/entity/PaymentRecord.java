package com.ticketflow.model.entity;

import java.time.LocalDateTime;

public record PaymentRecord(long id, long orderId, long amountFen, LocalDateTime paidAt) {}
