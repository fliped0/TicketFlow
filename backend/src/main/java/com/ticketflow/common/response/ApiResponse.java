package com.ticketflow.common.response;
import org.slf4j.MDC;
public record ApiResponse<T>(String code, String message, T data, String traceId, boolean replayed) {
 public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>("OK", "成功", data, MDC.get("traceId"), false); }
 public static ApiResponse<Object> error(String code, String message) { return new ApiResponse<>(code,message,null,MDC.get("traceId"),false); }
}
