package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.model.entity.PurchaseCatalog;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderPolicyTest {
    final LocalDateTime start=LocalDateTime.of(2026,10,1,2,0);
    final LocalDateTime end=start.plusHours(1);
    PurchaseCatalog catalog(String status) { return new PurchaseCatalog(1,2,3,"Show","City","Hall",status,end.plusHours(1),start,end,"Tier",58000,"FULL_BEFORE_START_V1"); }
    @Test void saleBoundariesArePreciseToOneMicrosecond() {
        assertEquals("SALE_NOT_STARTED",assertThrows(BusinessRejection.class,()->OrderPolicy.checkSale(catalog("ON_SALE"),start.minusNanos(1000))).code());
        assertDoesNotThrow(()->OrderPolicy.checkSale(catalog("ON_SALE"),start));
        assertDoesNotThrow(()->OrderPolicy.checkSale(catalog("ON_SALE"),start.plusNanos(1000)));
        assertDoesNotThrow(()->OrderPolicy.checkSale(catalog("ON_SALE"),end.minusNanos(1000)));
        for (var time:new LocalDateTime[]{end,end.plusNanos(1000)})
            assertEquals("SALE_ENDED",assertThrows(BusinessRejection.class,()->OrderPolicy.checkSale(catalog("ON_SALE"),time)).code());
    }
    @Test void statusPrecedesSaleWindowAndExpiryIsCappedAtStart() {
        assertEquals("NOT_ON_SALE",assertThrows(BusinessRejection.class,()->OrderPolicy.checkSale(catalog("OFF_SALE"),start.minusSeconds(1))).code());
        assertEquals(start.plusMinutes(15),OrderPolicy.expiry(start,start.plusHours(1)));
        assertEquals(start.plusMinutes(2),OrderPolicy.expiry(start,start.plusMinutes(2)));
        assertEquals(start.plusMinutes(15),OrderPolicy.expiry(start,start.plusMinutes(15)));
    }
    @Test void keyAndIdValidationRejectAmbiguousOrInvalidInputs() {
        assertDoesNotThrow(()->TradeExecutor.validateKey("a_0123456789-ABCD"));
        for (String key:new String[]{"short","a".repeat(65)," "+"a".repeat(16),"中文".repeat(16)})
            assertThrows(com.ticketflow.common.exception.BusinessException.class,()->TradeExecutor.validateKey(key));
        assertEquals(9223372036854775807L,OrderApplicationService.id("9223372036854775807"));
        for (String id:new String[]{"0","01","-1","1.0","9223372036854775808"})
            assertThrows(com.ticketflow.common.exception.BusinessException.class,()->OrderApplicationService.id(id));
    }
}
