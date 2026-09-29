package com.ticketflow.controller;

import com.ticketflow.common.response.ApiResponse;
import com.ticketflow.model.dto.CredentialsDTO;
import com.ticketflow.model.vo.TokenVO;
import com.ticketflow.model.vo.UserVO;
import com.ticketflow.service.IdentityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final IdentityService service;

    public AuthController(IdentityService service) {
        this.service = service;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<UserVO>> register(@RequestBody CredentialsDTO request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(service.register(request.username(), request.password())));
    }

    @PostMapping("/auth/login")
    public ApiResponse<TokenVO> login(@RequestBody CredentialsDTO request) {
        return ApiResponse.ok(service.login(request.username(), request.password()));
    }

    @GetMapping("/users/me")
    public ApiResponse<UserVO> me(Authentication authentication) {
        return ApiResponse.ok(service.getCurrentUser(Long.parseLong(authentication.getName())));
    }
}
