package com.ticketflow.model.vo;

public record OrderSnapshotVO(int schemaVersion, String eventId, String eventName, String city,
                              String venue, String sessionId, String startsAt, String tierId,
                              String tierName, long unitPriceFen, int quantity, long amountFen,
                              String refundPolicy) {}
