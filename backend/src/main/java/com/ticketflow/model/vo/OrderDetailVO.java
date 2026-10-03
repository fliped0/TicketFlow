package com.ticketflow.model.vo;

import tools.jackson.databind.JsonNode;

public record OrderDetailVO(String orderId, String status, int quantity, long unitPriceFen,
                            long amountFen, JsonNode snapshot, String createdAt, String expiresAt,
                            PaymentVO payment, RefundVO refund) {}
