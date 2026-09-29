package com.ticketflow.infrastructure;
import com.ticketflow.shared.*;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.LoggerFactory;
@RestControllerAdvice
public class ApiExceptionHandler {
 @ExceptionHandler(BusinessException.class)
 ResponseEntity<?> business(BusinessException e) { return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(),e.getMessage())); }
 @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, ConstraintViolationException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class, MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
 ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR","请求参数不合法")); }
 @ExceptionHandler(DataAccessException.class)
 ResponseEntity<?> database(DataAccessException e) {
  LoggerFactory.getLogger(getClass()).error("Database operation failed: {}",e.getClass().getSimpleName());
  return ResponseEntity.status(503).body(new ApiResponse<>("TEMPORARILY_UNAVAILABLE","服务暂不可用",java.util.Map.of("retryWithSameKey",true),org.slf4j.MDC.get("traceId"),false));
 }
 @ExceptionHandler(Exception.class)
 ResponseEntity<?> unexpected(Exception e) {
  LoggerFactory.getLogger(getClass()).error("Unhandled request failure: {}",e.getClass().getSimpleName());
  return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR","服务内部错误"));
 }
}
