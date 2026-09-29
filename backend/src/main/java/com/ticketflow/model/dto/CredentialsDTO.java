package com.ticketflow.model.dto;

/** Registration and login input. Passwords are validated without trimming. */
public record CredentialsDTO(String username, String password) {}
