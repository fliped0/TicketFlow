package com.ticketflow.model.vo;

public record TradeResultVO(String orderId, String operationStatus, String currentOrderStatus,
                            long amountFen, String expiresAt, String paymentId, String refundId) {
    public TradeResultVO(String orderId, String operationStatus, String currentOrderStatus, long amountFen, String expiresAt) {
        this(orderId,operationStatus,currentOrderStatus,amountFen,expiresAt,null,null);
    }
}
