package com.ticketflow.service;

import org.springframework.stereotype.Service;

/** Local simulation only. Failure behavior is overridden in isolated test configuration. */
@Service
public class PaymentSimulator {
    public boolean pay(long order, long amountFen) { return true; }
    public boolean refund(long order, long amountFen) { return true; }
}
