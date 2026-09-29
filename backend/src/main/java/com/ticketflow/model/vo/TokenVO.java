package com.ticketflow.model.vo;

public record TokenVO(String accessToken, String tokenType, int expiresIn) {}
