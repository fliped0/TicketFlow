package com.ticketflow.model.dto;

public final class CatalogDTO {
    private CatalogDTO() {}
    public record Event(String name, String description, String category, String city, String venue) {}
    public record EventUpdate(String name, String description, String category, String city, String venue, Long expectedVersion) {}
    public record Status(String status, Long expectedVersion) {}
    public record Session(String startsAt, String saleStartAt, String saleEndAt) {}
    public record SessionUpdate(String startsAt, String saleStartAt, String saleEndAt, Long expectedVersion) {}
    public record Tier(String name, Long priceFen, Integer capacity) {}
    public record TierUpdate(String name, Long priceFen, Integer capacity, Long expectedVersion) {}
}
