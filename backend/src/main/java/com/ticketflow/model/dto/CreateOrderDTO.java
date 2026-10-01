package com.ticketflow.model.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;

public record CreateOrderDTO(String tierId, Integer quantity) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static CreateOrderDTO fromJson(JsonNode input) {
        // Enforce the wire contract before Jackson can coerce numbers or truncate fractions.
        if (input==null || !input.isObject() || input.size()!=2 || !input.path("tierId").isString()
                || !input.path("quantity").toString().equals("1"))
            throw new IllegalArgumentException("Expected string tierId and integer quantity=1");
        return new CreateOrderDTO(input.path("tierId").asString(),1);
    }
}
