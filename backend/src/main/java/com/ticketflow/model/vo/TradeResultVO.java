package com.ticketflow.model.vo;

public record TradeResultVO(String orderId, String operationStatus, String currentOrderStatus,
                            long amountFen, String expiresAt) {}
