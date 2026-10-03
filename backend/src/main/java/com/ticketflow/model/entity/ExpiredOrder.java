package com.ticketflow.model.entity;

import java.time.LocalDateTime;

public record ExpiredOrder(long id, long userId, LocalDateTime expiresAt) {}
