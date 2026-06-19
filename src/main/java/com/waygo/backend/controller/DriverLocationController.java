package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.dto.order.DriverLocationPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.concurrent.ConcurrentHashMap;

@Controller
@RequiredArgsConstructor
public class DriverLocationController {

    private final SimpMessagingTemplate messagingTemplate;
    
    // In-memory cache for driver locations: orderId -> Location
    private final ConcurrentHashMap<Long, DriverLocationPayload> locationCache = new ConcurrentHashMap<>();

    @MessageMapping("/driver/location")
    public void handleDriverLocation(DriverLocationPayload payload) {
        if (payload.getOrderId() == null) return;
        
        // Cache the latest location
        locationCache.put(payload.getOrderId(), payload);
        
        // Broadcast to anyone subscribed to this order's location topic
        messagingTemplate.convertAndSend("/topic/orders/" + payload.getOrderId() + "/location", payload);
    }

    // Add REST endpoint to get the last known location for initialization
    @GetMapping("/api/v1/orders/{orderId}/driver-location")
    @ResponseBody
    public ResponseEntity<ApiResponse<DriverLocationPayload>> getDriverLocation(@PathVariable("orderId") Long orderId) {
        DriverLocationPayload cached = locationCache.get(orderId);
        if (cached == null) {
            return ResponseEntity.ok(ApiResponse.success(null, "No location cached yet"));
        }
        return ResponseEntity.ok(ApiResponse.success(cached, "Driver location retrieved successfully"));
    }
}
