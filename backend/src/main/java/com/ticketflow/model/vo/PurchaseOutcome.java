package com.ticketflow.model.vo;
public record PurchaseOutcome(int httpStatus,String code,String message,PurchaseRequestVO data,boolean replayed) {}
