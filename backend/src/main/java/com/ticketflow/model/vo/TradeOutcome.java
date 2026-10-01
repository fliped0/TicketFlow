package com.ticketflow.model.vo;

public record TradeOutcome(int httpStatus, String code, String message, TradeResultVO data,
                           boolean replayed) {}
