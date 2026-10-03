package com.ticketflow.controller;

import com.ticketflow.common.response.ApiResponse;
import com.ticketflow.model.dto.CreateOrderDTO;
import com.ticketflow.model.dto.EmptyTradeDTO;
import com.ticketflow.model.vo.*;
import com.ticketflow.service.OrderApplicationService;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class TradeController {
    private final OrderApplicationService service;
    public TradeController(OrderApplicationService service) { this.service=service; }
    private static long actor(Authentication auth) { return Long.parseLong(auth.getName()); }
    @PostMapping
    public ResponseEntity<ApiResponse<TradeResultVO>> create(Authentication auth, @RequestHeader("Idempotency-Key") String key,
                                                            @RequestBody CreateOrderDTO input) {
        var result=service.create(actor(auth),key,input);
        return ResponseEntity.status(result.httpStatus()).body(new ApiResponse<>(result.code(),result.message(),result.data(),MDC.get("traceId"),result.replayed()));
    }
    @GetMapping
    public ApiResponse<OrderPageVO> list(Authentication auth, @RequestParam(required=false) String status,
                                         @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.list(actor(auth),status,page,size));
    }
    @GetMapping("/{id}")
    public ApiResponse<OrderDetailVO> detail(Authentication auth, @PathVariable String id) { return ApiResponse.ok(service.detail(actor(auth),id)); }
    private static ResponseEntity<ApiResponse<TradeResultVO>> response(TradeOutcome result) {
        return ResponseEntity.status(result.httpStatus()).body(new ApiResponse<>(result.code(),result.message(),result.data(),MDC.get("traceId"),result.replayed()));
    }
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<TradeResultVO>> cancel(Authentication auth,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody EmptyTradeDTO input) {
        return response(service.cancel(actor(auth),key,id));
    }
    @PostMapping("/{id}/payments")
    public ResponseEntity<ApiResponse<TradeResultVO>> pay(Authentication auth,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody EmptyTradeDTO input) {
        return response(service.pay(actor(auth),key,id));
    }
    @PostMapping("/{id}/refunds")
    public ResponseEntity<ApiResponse<TradeResultVO>> refund(Authentication auth,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody EmptyTradeDTO input) {
        return response(service.refund(actor(auth),key,id));
    }
}
