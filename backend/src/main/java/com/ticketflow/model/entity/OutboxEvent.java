package com.ticketflow.model.entity;
public record OutboxEvent(String id,String destination,String type,String aggregate,long session,long tier,long epoch,
        Long sequence,String payload,long claimVersion,int attempts) {}
