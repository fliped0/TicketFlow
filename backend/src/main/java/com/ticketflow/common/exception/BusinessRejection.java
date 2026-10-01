package com.ticketflow.common.exception;

/** A known trade rule rejection; only this type is persisted after savepoint rollback. */
public class BusinessRejection extends BusinessException {
    public BusinessRejection(int status, String code, String message) { super(status, code, message); }
}
