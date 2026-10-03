package com.ticketflow.model.entity;

public record AdminOrderRecord(OrderRecord order, PaymentRecord payment, RefundRecord refund) {}
