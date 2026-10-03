package com.ticketflow.controller;

import com.ticketflow.common.response.ApiResponse;
import com.ticketflow.model.vo.*;
import com.ticketflow.service.AdminOrderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrderController {
    private final AdminOrderService service;
    public AdminOrderController(AdminOrderService service) { this.service=service; }
    @GetMapping("/orders")
    public ApiResponse<CatalogVO.Page<AdminOrderVO>> orders(@RequestParam(required=false) String orderId,@RequestParam(required=false) String sessionId,
            @RequestParam(required=false) String status,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.orders(orderId,sessionId,status,from,to,page,size));
    }
    @GetMapping("/statistics")
    public ApiResponse<StatisticsVO> statistics(@RequestParam String from,@RequestParam String to) { return ApiResponse.ok(service.statistics(from,to)); }
}
