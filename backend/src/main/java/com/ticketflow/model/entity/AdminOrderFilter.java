package com.ticketflow.model.entity;

import java.time.LocalDateTime;

public record AdminOrderFilter(Long orderId, Long sessionId, String status, LocalDateTime from, LocalDateTime to) {}
