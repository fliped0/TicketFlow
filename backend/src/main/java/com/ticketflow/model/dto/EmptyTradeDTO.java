package com.ticketflow.model.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;

public record EmptyTradeDTO() {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static EmptyTradeDTO fromJson(JsonNode input) {
        if (input==null || !input.isObject() || input.size()!=0) throw new IllegalArgumentException("Expected empty object");
        return new EmptyTradeDTO();
    }
}
