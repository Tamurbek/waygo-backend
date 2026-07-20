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
import com.waygo.backend.entity.RideBooking;

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

    @PostMapping("/{orderId}/lock")
    @Operation(summary = "Driver locks a passenger's order for exclusive viewing")
    public ResponseEntity<ApiResponse<Order>> lock(@PathVariable("orderId") Long orderId) {
        Order order = orderService.lockOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(order, "Order locked successfully"));
    }

    @PostMapping("/{orderId}/unlock")
    @Operation(summary = "Driver unlocks a passenger's order")
    public ResponseEntity<ApiResponse<Order>> unlock(@PathVariable("orderId") Long orderId) {
        Order order = orderService.unlockOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(order, "Order unlocked successfully"));
    }

    @PostMapping("/{orderId}/accept")
    @Operation(summary = "Driver accepts an order with offered available seats and custom price per person")
    public ResponseEntity<ApiResponse<Order>> accept(
            @PathVariable("orderId") Long orderId,
            @RequestParam(required = false) List<String> availableSeats,
            @RequestParam(required = false) java.math.BigDecimal pricePerPerson) {
        Order order = orderService.acceptOrder(orderId, availableSeats, pricePerPerson);
        return ResponseEntity.ok(ApiResponse.success(order, "Order accepted/bid placed by driver"));
    }

    @PostMapping("/{orderId}/confirm-driver")
    @Operation(summary = "Passenger confirms a driver offer or default accepted driver")
    public ResponseEntity<ApiResponse<Order>> confirmDriver(
            @PathVariable("orderId") Long orderId,
            @RequestParam(required = false) Long offerId,
            @RequestBody(required = false) List<String> selectedSeats) {
        if (offerId != null) {
            Order order = orderService.confirmDriverOffer(orderId, offerId, selectedSeats);
            return ResponseEntity.ok(ApiResponse.success(order, "Driver offer confirmed successfully"));
        } else {
            Order order = orderService.confirmDriver(orderId);
            return ResponseEntity.ok(ApiResponse.success(order, "Driver confirmed successfully"));
        }
    }

    @PostMapping("/{orderId}/reject-driver")
    @Operation(summary = "Passenger rejects a driver offer or default accepted driver")
    public ResponseEntity<ApiResponse<Order>> rejectDriver(
            @PathVariable("orderId") Long orderId,
            @RequestParam(required = false) Long offerId) {
        if (offerId != null) {
            Order order = orderService.rejectDriverOffer(orderId, offerId);
            return ResponseEntity.ok(ApiResponse.success(order, "Driver offer rejected successfully"));
        } else {
            Order order = orderService.rejectDriver(orderId);
            return ResponseEntity.ok(ApiResponse.success(order, "Driver rejected successfully"));
        }
    }

    @PostMapping("/{orderId}/cancel-offer")
    @Operation(summary = "Driver cancels/withdraws their pending offer")
    public ResponseEntity<ApiResponse<Order>> cancelOffer(@PathVariable("orderId") Long orderId) {
        Order order = orderService.cancelDriverOffer(orderId);
        return ResponseEntity.ok(ApiResponse.success(order, "Driver offer cancelled successfully"));
    }


    @PostMapping("/{orderId}/assign-seats")
    @Operation(summary = "Driver assigns passenger seats in the vehicle saloon")
    public ResponseEntity<ApiResponse<Order>> assignSeats(
            @PathVariable("orderId") Long orderId,
            @RequestBody java.util.List<String> selectedSeats) {
        Order order = orderService.assignSeats(orderId, selectedSeats);
        return ResponseEntity.ok(ApiResponse.success(order, "Seats assigned successfully"));
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

    @PostMapping("/{orderId}/rate")
    @Operation(summary = "Passenger rates the driver and provides feedback/comment")
    public ResponseEntity<ApiResponse<Order>> rate(
            @PathVariable("orderId") Long orderId,
            @RequestParam Double rating,
            @RequestParam(value = "comment", required = false) String comment) {
        Order order = orderService.rateDriver(orderId, rating, comment);
        return ResponseEntity.ok(ApiResponse.success(order, "Driver rated successfully"));
    }

    @GetMapping("/pending")
    @Operation(summary = "Get list of orders waiting for a driver")
    public ResponseEntity<ApiResponse<List<Order>>> getPending(@RequestParam(value = "region", required = false) String region) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getPendingOrders(region), "Pending orders retrieved"));
    }

    @GetMapping("/history/passenger/{userId}")
    @Operation(summary = "Get trip history for a passenger")
    public ResponseEntity<ApiResponse<List<Order>>> getPassengerHistory(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getPassengerHistory(userId), "User history retrieved"));
    }

    @GetMapping("/history/driver/{userId}")
    @Operation(summary = "Get trip history for a driver")
    public ResponseEntity<ApiResponse<List<Order>>> getDriverHistory(@PathVariable("userId") Long userId) {
        List<Order> history = orderService.getDriverHistory(userId);
        if (!history.isEmpty()) {
            try {
                System.out.println("DEBUG HISTORY ORDER JSON: " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(history.get(0)));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return ResponseEntity.ok(ApiResponse.success(history, "Driver history retrieved"));
    }

    @GetMapping("/my-bookings")
    @Operation(summary = "Get current passenger's active bookings with parent order IDs")
    public ResponseEntity<ApiResponse<List<RideBooking>>> getMyBookings() {
        return ResponseEntity.ok(ApiResponse.success(orderService.getMyBookings(), "My bookings retrieved"));
    }

    @PostMapping("/{orderId}/book")
    @Operation(summary = "Passenger requests to book seats in driver's ride offer")
    public ResponseEntity<ApiResponse<Order>> book(
            @PathVariable("orderId") Long orderId,
            @RequestBody java.util.List<String> selectedSeats) {
        Order order = orderService.bookRide(orderId, selectedSeats);
        return ResponseEntity.ok(ApiResponse.success(order, "Booking request sent to driver"));
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    @Operation(summary = "Driver confirms a passenger seat booking")
    public ResponseEntity<ApiResponse<Order>> confirmBooking(@PathVariable("bookingId") Long bookingId) {
        Order order = orderService.confirmBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.success(order, "Booking confirmed successfully"));
    }

    @PostMapping("/bookings/{bookingId}/collect")
    @Operation(summary = "Driver marks a passenger booking as collected (picked up)")
    public ResponseEntity<ApiResponse<Order>> collectBooking(@PathVariable("bookingId") Long bookingId) {
        Order order = orderService.collectBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.success(order, "Booking marked as collected successfully"));
    }

    @PostMapping("/bookings/{bookingId}/uncollect")
    @Operation(summary = "Driver marks a collected passenger booking back to accepted (uncollected)")
    public ResponseEntity<ApiResponse<Order>> uncollectBooking(@PathVariable("bookingId") Long bookingId) {
        Order order = orderService.uncollectBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.success(order, "Booking marked as uncollected successfully"));
    }

    @PostMapping("/bookings/{bookingId}/reject")
    @Operation(summary = "Driver rejects a passenger seat booking")
    public ResponseEntity<ApiResponse<Order>> rejectBooking(
            @PathVariable("bookingId") Long bookingId,
            @RequestParam(value = "seat", required = false) String seat) {
        Order order = orderService.rejectBooking(bookingId, seat);
        return ResponseEntity.ok(ApiResponse.success(order, "Booking rejected successfully"));
    }

    @DeleteMapping("/bookings/{bookingId}")
    @Operation(summary = "Passenger cancels their seat booking")
    public ResponseEntity<ApiResponse<Order>> cancelBooking(
            @PathVariable("bookingId") Long bookingId,
            @RequestParam(value = "seat", required = false) String seat) {
        Order order = orderService.cancelBooking(bookingId, seat);
        return ResponseEntity.ok(ApiResponse.success(order, "Booking cancelled successfully"));
    }
}
