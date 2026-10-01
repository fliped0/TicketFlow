package com.ticketflow.model.entity;

import java.time.LocalDateTime;

public record PurchaseCatalog(long eventId, long sessionId, long tierId, String eventName,
                              String city, String venue, String eventStatus, LocalDateTime startsAt,
                              LocalDateTime saleStartAt, LocalDateTime saleEndAt, String tierName,
                              long priceFen, String refundPolicy) {}
