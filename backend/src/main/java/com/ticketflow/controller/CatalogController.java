package com.ticketflow.controller;

import com.ticketflow.common.response.ApiResponse;
import com.ticketflow.model.dto.CatalogDTO;
import com.ticketflow.model.vo.CatalogVO;
import com.ticketflow.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service=service; }
    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }

    @PostMapping("/admin/events")
    public ResponseEntity<ApiResponse<CatalogVO.Event>> createEvent(Authentication auth,@RequestBody CatalogDTO.Event input) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.createEvent(actor(auth),input)));
    }
    @PutMapping("/admin/events/{id}")
    public ApiResponse<CatalogVO.Event> updateEvent(Authentication auth,@PathVariable long id,@RequestBody CatalogDTO.EventUpdate input) {
        return ApiResponse.ok(service.updateEvent(actor(auth),id,input));
    }
    @PutMapping("/admin/events/{id}/status")
    public ApiResponse<CatalogVO.Event> status(Authentication auth,@PathVariable long id,@RequestBody CatalogDTO.Status input) {
        return ApiResponse.ok(service.setStatus(actor(auth),id,input));
    }
    @PostMapping("/admin/events/{id}/sessions")
    public ResponseEntity<ApiResponse<CatalogVO.Session>> createSession(Authentication auth,@PathVariable long id,@RequestBody CatalogDTO.Session input) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.createSession(actor(auth),id,input)));
    }
    @PutMapping("/admin/sessions/{id}")
    public ApiResponse<CatalogVO.Session> updateSession(Authentication auth,@PathVariable long id,@RequestBody CatalogDTO.SessionUpdate input) {
        return ApiResponse.ok(service.updateSession(actor(auth),id,input));
    }
    @PostMapping("/admin/sessions/{id}/tiers")
    public ResponseEntity<ApiResponse<CatalogVO.Tier>> createTier(Authentication auth,@PathVariable long id,@RequestBody CatalogDTO.Tier input) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.createTier(actor(auth),id,input)));
    }
    @PutMapping("/admin/tiers/{id}")
    public ApiResponse<CatalogVO.Tier> updateTier(Authentication auth,@PathVariable long id,@RequestBody CatalogDTO.TierUpdate input) {
        return ApiResponse.ok(service.updateTier(actor(auth),id,input));
    }
    @GetMapping("/events")
    public ApiResponse<CatalogVO.Page<CatalogVO.Event>> publicEvents(@RequestParam(required=false) String keyword,@RequestParam(required=false) String city,@RequestParam(required=false) String category,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.events(false,null,keyword,city,category,page,size));
    }
    @GetMapping("/events/{id}")
    public ApiResponse<CatalogVO.Event> publicEvent(@PathVariable long id) { return ApiResponse.ok(service.event(id,false)); }
    @GetMapping("/events/{id}/sessions")
    public ApiResponse<CatalogVO.Page<CatalogVO.Session>> publicSessions(@PathVariable long id,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.sessions(id,false,page,size));
    }
    @GetMapping("/sessions/{id}/tiers")
    public ApiResponse<CatalogVO.Page<CatalogVO.Tier>> publicTiers(@PathVariable long id,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.tiers(id,false,page,size));
    }
    @GetMapping("/admin/events")
    public ApiResponse<CatalogVO.Page<CatalogVO.Event>> adminEvents(@RequestParam(required=false) String status,@RequestParam(required=false) String keyword,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.events(true,status,keyword,null,null,page,size));
    }
    @GetMapping("/admin/events/{id}")
    public ApiResponse<CatalogVO.Event> adminEvent(@PathVariable long id) { return ApiResponse.ok(service.event(id,true)); }
    @GetMapping("/admin/events/{id}/sessions")
    public ApiResponse<CatalogVO.Page<CatalogVO.Session>> adminSessions(@PathVariable long id,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.sessions(id,true,page,size));
    }
    @GetMapping("/admin/sessions/{id}/tiers")
    public ApiResponse<CatalogVO.Page<CatalogVO.Tier>> adminTiers(@PathVariable long id,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.tiers(id,true,page,size));
    }
}
