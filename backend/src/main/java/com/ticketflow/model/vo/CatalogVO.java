package com.ticketflow.model.vo;

import java.util.List;

public final class CatalogVO {
    private CatalogVO() {}
    public record Event(String id, String name, String description, String category, String city,
                        String venue, String status, long version) {}
    public record Session(String id, String eventId, String startsAt, String saleStartAt,
                          String saleEndAt, String saleStatus, long version) {}
    public record Tier(String id, String sessionId, String name, long priceFen, int available,
                       int capacity, String saleStatus, String refundPolicy, long version) {}
    public record Page<T>(List<T> items, int page, int size, long total) {}
}
