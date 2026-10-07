package com.ticketflow.controller;
import com.ticketflow.common.response.ApiResponse;
import com.ticketflow.model.dto.*;
import com.ticketflow.model.vo.*;
import com.ticketflow.service.*;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class PurchaseRequestController {
    private final PurchaseRequestService requests;private final PurchaseModeService modes;
    public PurchaseRequestController(PurchaseRequestService requests,PurchaseModeService modes) {this.requests=requests;this.modes=modes;}
    @PostMapping("/purchase-requests")
    public ResponseEntity<ApiResponse<PurchaseRequestVO>> submit(Authentication auth,@RequestHeader("Idempotency-Key")String key,@RequestBody CreateOrderDTO input) {
        var result=requests.submit(Long.parseLong(auth.getName()),key,input);
        var response=ResponseEntity.status(result.httpStatus());if(result.data()!=null)response.header("Location","/api/v1/purchase-requests/"+result.data().requestId());
        if(result.httpStatus()==202)response.header("Retry-After","1");
        return response.body(new ApiResponse<>(result.code(),result.message(),result.data(),MDC.get("traceId"),result.replayed()));
    }
    @GetMapping("/purchase-requests/{id}")
    public ResponseEntity<ApiResponse<PurchaseRequestVO>> get(Authentication auth,@PathVariable String id) {
        var result=requests.get(Long.parseLong(auth.getName()),id);var response=ResponseEntity.ok();
        if(!java.util.List.of("SUCCEEDED","REJECTED").contains(result.state()))response.header("Retry-After","1");return response.body(ApiResponse.ok(result));
    }
    @PutMapping("/admin/sessions/{id}/purchase-mode")
    public ApiResponse<PurchaseModeVO> mode(Authentication auth,@PathVariable String id,@RequestBody PurchaseModeDTO input) {return ApiResponse.ok(modes.change(Long.parseLong(auth.getName()),OrderApplicationService.id(id),input));}
}
