package com.ticketflow.model.vo;
import tools.jackson.databind.JsonNode;
public record PurchaseRequestVO(String requestId,String state,String acceptedAt,String createDeadline,String orderId,
        String failureCode,String currentOrderStatus,JsonNode snapshot) {}
