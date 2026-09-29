package com.ticketflow.model.vo;

/** Public user data, without the stored password hash. */
public record UserVO(String userId, String username, String role) {}
