package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.dto.order.OrderCreateDTO;
import com.waygo.backend.entity.Order;
import com.waygo.backend.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Order Controller", description = "Endpoints for taxi booking and trip management")
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/create")
    @Operation(summary = "Passenger creates a new booking request")
    public ResponseEntity<ApiResponse<Order>> create(@Valid @RequestBody OrderCreateDTO dto) {
        Order order = orderService.createOrder(dto);
        return ResponseEntity.ok(ApiResponse.success(order, "Order created successfully"));
    }

    @PutMapping("/{orderId}")
    @Operation(summary = "Passenger updates an existing booking request")
    public ResponseEntity<ApiResponse<Order>> update(@PathVariable("orderId") Long orderId, @Valid @RequestBody OrderCreateDTO dto) {
        Order order = orderService.updateOrder(orderId, dto);
        return ResponseEntity.ok(ApiResponse.success(order, "Order updated successfully"));
    }

    @PostMapping("/{orderId}/accept")
    @Operation(summary = "Driver accepts an order")
    public ResponseEntity<ApiResponse<Order>> accept(@PathVariable("orderId") Long orderId) {
        Order order = orderService.acceptOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(order, "Order accepted by driver"));
    }

    @PostMapping("/{orderId}/join")
    @Operation(summary = "Passenger joins a ride offer")
    public ResponseEntity<ApiResponse<Order>> join(@PathVariable("orderId") Long orderId) {
        Order order = orderService.joinOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(order, "Joined the ride successfully"));
    }

    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Update trip status (ARRIVED, STARTED, etc.)")
    public ResponseEntity<ApiResponse<Order>> updateStatus(
            @PathVariable("orderId") Long orderId,
            @RequestParam Order.OrderStatus status) {
        
        Order order = orderService.updateStatus(orderId, status);
        return ResponseEntity.ok(ApiResponse.success(order, "Status updated to " + status));
    }

    @PostMapping("/{orderId}/complete")
    @Operation(summary = "Complete trip and process payment")
    public ResponseEntity<ApiResponse<Order>> complete(@PathVariable("orderId") Long orderId) {
        Order order = orderService.completeTrip(orderId);
        return ResponseEntity.ok(ApiResponse.success(order, "Trip completed and payment processed"));
    }

    @GetMapping("/pending")
    @Operation(summary = "Get list of orders waiting for a driver")
    public ResponseEntity<ApiResponse<List<Order>>> getPending() {
        return ResponseEntity.ok(ApiResponse.success(orderService.getPendingOrders(), "Pending orders retrieved"));
    }

    @GetMapping("/history/passenger/{userId}")
    @Operation(summary = "Get trip history for a passenger")
    public ResponseEntity<ApiResponse<List<Order>>> getPassengerHistory(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getPassengerHistory(userId), "User history retrieved"));
    }

    @GetMapping("/history/driver/{userId}")
    @Operation(summary = "Get trip history for a driver")
    public ResponseEntity<ApiResponse<List<Order>>> getDriverHistory(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getDriverHistory(userId), "Driver history retrieved"));
    }
}
