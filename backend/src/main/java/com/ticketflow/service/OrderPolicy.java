package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessRejection;
import com.ticketflow.model.entity.PurchaseCatalog;
import java.time.LocalDateTime;

public final class OrderPolicy {
    private OrderPolicy() {}
    public static void checkSale(PurchaseCatalog catalog, LocalDateTime now) {
        if (!catalog.eventStatus().equals("ON_SALE")) throw rejected("NOT_ON_SALE");
        if (now.isBefore(catalog.saleStartAt())) throw rejected("SALE_NOT_STARTED");
        if (!now.isBefore(catalog.saleEndAt())) throw rejected("SALE_ENDED");
    }
    public static LocalDateTime expiry(LocalDateTime now, LocalDateTime startsAt) {
        return now.plusMinutes(15).isBefore(startsAt) ? now.plusMinutes(15) : startsAt;
    }
    public static BusinessRejection rejected(String code) { return new BusinessRejection(409,code,"下单条件不满足"); }
    public static void checkPayment(LocalDateTime now, LocalDateTime expiry) {
        if (!now.isBefore(expiry)) throw new BusinessRejection(409,"ORDER_EXPIRED","订单已过支付期限");
    }
    public static void checkRefund(LocalDateTime now, LocalDateTime startsAt) {
        if (!now.isBefore(startsAt)) throw new BusinessRejection(409,"REFUND_CLOSED","已到开场时间");
    }
}
