package com.waygo.backend.service;

import com.waygo.backend.dto.order.DriverLocationPayload;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared in-memory store of the latest known location per driver/order,
 * previously private to DriverLocationController. Extracted so OrderService
 * can also read a driver's current position (e.g. to sort pending orders by
 * distance) without the service layer depending on a web controller.
 */
@Component
public class DriverLocationCache {

    private final ConcurrentHashMap<Long, DriverLocationPayload> byOrderId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, DriverLocationPayload> byDriverId = new ConcurrentHashMap<>();

    public void putByOrderId(Long orderId, DriverLocationPayload payload) {
        byOrderId.put(orderId, payload);
    }

    public DriverLocationPayload getByOrderId(Long orderId) {
        return byOrderId.get(orderId);
    }

    public void putByDriverId(Long driverId, DriverLocationPayload payload) {
        byDriverId.put(driverId, payload);
    }

    public DriverLocationPayload getByDriverId(Long driverId) {
        return byDriverId.get(driverId);
    }
}
