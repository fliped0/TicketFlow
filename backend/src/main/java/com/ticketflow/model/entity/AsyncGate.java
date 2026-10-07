package com.ticketflow.model.entity;
public record AsyncGate(long sessionId,long epoch,String phase,String mode) {}
