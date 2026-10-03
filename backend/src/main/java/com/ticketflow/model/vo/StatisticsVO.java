package com.ticketflow.model.vo;

public record StatisticsVO(String from, String to, long orderCount, long paidAmountFen,
                           long refundAmountFen, long netAmountFen, String orderTimeBasis,
                           String paymentTimeBasis, String refundTimeBasis) {}
