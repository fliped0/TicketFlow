package com.ticketflow.common.web;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
 protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
  String id=UUID.randomUUID().toString();
  MDC.put("traceId",id); response.setHeader("X-Trace-Id",id);
  long started=System.nanoTime();
  try { chain.doFilter(request,response); } finally {
   int status=response.getStatus();
   String outcome=status>=500?"SYSTEM_ERROR":status>=400?"REJECTED":"SUCCESS";
   LoggerFactory.getLogger(getClass()).info("request_complete traceId={} method={} path={} status={} outcome={} elapsedMs={}",
    id,request.getMethod(),request.getRequestURI(),status,outcome,(System.nanoTime()-started)/1_000_000);
   MDC.remove("traceId");
  }
 }
}
