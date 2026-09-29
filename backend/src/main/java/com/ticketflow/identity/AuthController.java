package com.ticketflow.identity;
import com.ticketflow.shared.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
@RestController @RequestMapping("/api/v1")
public class AuthController {
 private final IdentityService service; private final UserMapper users;
 public AuthController(IdentityService service,UserMapper users) { this.service=service;this.users=users; }
 public record Credentials(String username,String password) {}
 @PostMapping("/auth/register") ResponseEntity<?> register(@RequestBody Credentials request) { return ResponseEntity.status(201).body(ApiResponse.ok(service.register(request.username(),request.password()))); }
 @PostMapping("/auth/login") Object login(@RequestBody Credentials request) { return ApiResponse.ok(service.login(request.username(),request.password())); }
 @GetMapping("/users/me") Object me(Authentication authentication) {
  UserAccount user=users.byId(Long.parseLong(authentication.getName()));
  return ApiResponse.ok(new IdentityService.UserView(user.id().toString(),user.username(),user.role()));
 }
}
