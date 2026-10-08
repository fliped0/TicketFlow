package com.ticketflow.model.entity;

/** Server-issued admission receipt. Never accepted from an HTTP client. */
public record AsyncToken(String token, String id, long user, String key, String hash,
                         long tier, long at, String state, String order) {}
