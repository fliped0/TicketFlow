package com.ticketflow.identity;
public record UserAccount(Long id, String username, String passwordHash, String role, boolean enabled) {}
