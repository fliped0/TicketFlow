package com.ticketflow.model.entity;
public record AsyncReservation(String code,String requestId,String token,long reservedAtMillis) {}
