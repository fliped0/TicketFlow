package com.ticketflow.model.entity;
public record UserAccount(Long id, String username, String passwordHash, String role, boolean enabled) {}
