package com.ticketflow.model.vo;

import java.util.List;

public record OrderPageVO(List<OrderDetailVO> items, int page, int size, long total) {}
