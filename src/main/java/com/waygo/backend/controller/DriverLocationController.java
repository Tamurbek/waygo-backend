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
    
    // In-memory cache for global driver locations: driverId -> Location
    private final ConcurrentHashMap<Long, DriverLocationPayload> driverGlobalLocationCache = new ConcurrentHashMap<>();

    @MessageMapping("/driver/location")
    public void handleDriverLocation(DriverLocationPayload payload) {
        // Global tracking by driverId
        if (payload.getDriverId() != null) {
            driverGlobalLocationCache.put(payload.getDriverId(), payload);
            messagingTemplate.convertAndSend("/topic/drivers/" + payload.getDriverId() + "/location", payload);
        }

        // Active trip tracking by orderId
        if (payload.getOrderId() != null && payload.getOrderId() != 0) {
            locationCache.put(payload.getOrderId(), payload);
            messagingTemplate.convertAndSend("/topic/orders/" + payload.getOrderId() + "/location", payload);
        }
    }

    // Existing REST endpoint to get the last known location for initialization by orderId
    @GetMapping("/api/v1/orders/{orderId}/driver-location")
    @ResponseBody
    public ResponseEntity<ApiResponse<DriverLocationPayload>> getDriverLocation(@PathVariable("orderId") Long orderId) {
        DriverLocationPayload cached = locationCache.get(orderId);
        if (cached == null) {
            return ResponseEntity.ok(ApiResponse.success(null, "No location cached yet"));
        }
        return ResponseEntity.ok(ApiResponse.success(cached, "Driver location retrieved successfully"));
    }

    // New REST endpoint to get the global location by driverId
    @GetMapping("/api/v1/drivers/{driverId}/location")
    @ResponseBody
    public ResponseEntity<ApiResponse<DriverLocationPayload>> getGlobalDriverLocation(@PathVariable("driverId") Long driverId) {
        DriverLocationPayload cached = driverGlobalLocationCache.get(driverId);
        if (cached == null) {
            return ResponseEntity.ok(ApiResponse.success(null, "No global location cached yet"));
        }
        return ResponseEntity.ok(ApiResponse.success(cached, "Driver global location retrieved successfully"));
    }
}
