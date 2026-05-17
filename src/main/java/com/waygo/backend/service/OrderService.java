package com.waygo.backend.service;

import com.waygo.backend.dto.order.OrderCreateDTO;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.User;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.exception.UnauthorizedAccessException;
import com.waygo.backend.repository.DriverProfileRepository;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TransactionService transactionService;
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final DriverProfileRepository driverProfileRepository;
    private final com.waygo.backend.repository.RideBookingRepository rideBookingRepository;

    @Transactional
    public Order createOrder(OrderCreateDTO dto) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedAccessException("You must be logged in to create an order");
        }

        Order.OrderBuilder orderBuilder = Order.builder()
                .fromAddress(dto.getFromAddress())
                .toAddress(dto.getToAddress())
                .fromLat(dto.getFromLat())
                .fromLon(dto.getFromLon())
                .toLat(dto.getToLat())
                .toLon(dto.getToLon())
                .departureDate(dto.getDepartureDate())
                .departureTime(dto.getDepartureTime())
                .availableSeats(dto.getAvailableSeats())
                .passengerCount(dto.getPassengerCount())
                .notes(dto.getNotes())
                .price(dto.getPrice())
                .baggageDescription(dto.getBaggageDescription())
                .hasAirConditioning(dto.getServices() != null ? dto.getServices().getKonditsioner() : false)
                .hasBaggage(dto.getServices() != null ? dto.getServices().getBagaj() : false)
                .hasChildSeat(dto.getServices() != null ? dto.getServices().getChildSeat() : false)
                .hasTrailer(dto.getServices() != null ? dto.getServices().getTirkama() : false)
                .status(Order.OrderStatus.PENDING);

        if (currentUser.getRole() == User.Role.DRIVER) {
            orderBuilder.driver(currentUser);
        } else {
            orderBuilder.passenger(currentUser);
        }

        Order order = orderBuilder.build();
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyNewOrder(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order acceptOrder(Long orderId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can accept orders");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() != Order.OrderStatus.PENDING || order.getDriver() != null) {
             if (order.getDriver() != null && order.getPassenger() != null) {
                 throw new IllegalStateException("Order is no longer available for acceptance");
             }
        }

        order.setDriver(driver);
        order.setStatus(Order.OrderStatus.ACCEPTED);
        
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    public Order joinOrder(Long orderId) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can join ride offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() != Order.OrderStatus.PENDING || order.getPassenger() != null) {
            throw new IllegalStateException("Ride offer is no longer available");
        }

        order.setPassenger(passenger);
        order.setStatus(Order.OrderStatus.ACCEPTED);

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order completeTrip(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null || (!currentUser.getId().equals(order.getDriver().getId()) && currentUser.getRole() != User.Role.ADMIN)) {
            throw new UnauthorizedAccessException("Only the assigned driver or admin can complete the trip");
        }

        if (order.getStatus() != Order.OrderStatus.STARTED && order.getStatus() != Order.OrderStatus.ACCEPTED) {
            throw new IllegalStateException("Trip must be accepted or started to be completed");
        }

        // Process final payment automatically if passenger exists
        if (order.getPassenger() != null) {
            transactionService.processPayment(order.getPassenger().getId(), order.getDriver().getId(), order.getPrice());
        }

        order.setStatus(Order.OrderStatus.COMPLETED);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        
        User currentUser = securityUtils.getCurrentUser();
        boolean isPassenger = currentUser != null && order.getPassenger() != null && currentUser.getId().equals(order.getPassenger().getId());
        boolean isDriver = currentUser != null && order.getDriver() != null && currentUser.getId().equals(order.getDriver().getId());
        
        if (!isPassenger && !isDriver) {
            throw new UnauthorizedAccessException("You are not part of this order");
        }

        if (status == Order.OrderStatus.CANCELLED && isDriver) {
            // Driver is cancelling/rejecting their acceptance of a passenger's order!
            // Instead of setting the order to CANCELLED, we release it back to PENDING!
            order.setStatus(Order.OrderStatus.PENDING);
            order.setDriver(null);
            
            Order savedOrder = orderRepository.save(order);
            
            // Notify the passenger about the release (they will see it went back to pending)
            notificationService.notifyOrderStatusUpdate(savedOrder);
            
            // Notify all other drivers about the newly available pending order!
            notificationService.notifyNewOrder(savedOrder);
            
            return savedOrder;
        }

        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    public List<Order> getPassengerHistory(Long passengerId) {
        return orderRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId);
    }

    public List<Order> getDriverHistory(Long driverId) {
        return orderRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
    }

    @Transactional
    public Order updateOrder(Long orderId, OrderCreateDTO dto) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User currentUser = securityUtils.getCurrentUser();
        boolean isOwner = (order.getPassenger() != null && currentUser.getId().equals(order.getPassenger().getId())) ||
                         (order.getDriver() != null && currentUser.getId().equals(order.getDriver().getId()));
        
        if (currentUser == null || !isOwner) {
            throw new UnauthorizedAccessException("You can only edit your own orders");
        }

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("You can only edit orders that are still pending");
        }

        if (dto.getFromAddress() != null) order.setFromAddress(dto.getFromAddress());
        if (dto.getToAddress() != null) order.setToAddress(dto.getToAddress());
        if (dto.getFromLat() != null) order.setFromLat(dto.getFromLat());
        if (dto.getFromLon() != null) order.setFromLon(dto.getFromLon());
        if (dto.getToLat() != null) order.setToLat(dto.getToLat());
        if (dto.getToLon() != null) order.setToLon(dto.getToLon());
        if (dto.getDepartureDate() != null) order.setDepartureDate(dto.getDepartureDate());
        if (dto.getDepartureTime() != null) order.setDepartureTime(dto.getDepartureTime());
        if (dto.getPassengerCount() != null) order.setPassengerCount(dto.getPassengerCount());
        if (dto.getAvailableSeats() != null) {
            if (order.getAvailableSeats() == null) {
                order.setAvailableSeats(new java.util.ArrayList<>());
            }
            order.getAvailableSeats().clear();
            order.getAvailableSeats().addAll(dto.getAvailableSeats());
        }
        if (dto.getNotes() != null) order.setNotes(dto.getNotes());
        if (dto.getPrice() != null) order.setPrice(dto.getPrice());
        if (dto.getBaggageDescription() != null) order.setBaggageDescription(dto.getBaggageDescription());
        
        if (dto.getServices() != null) {
            if (dto.getServices().getKonditsioner() != null) order.setHasAirConditioning(dto.getServices().getKonditsioner());
            if (dto.getServices().getBagaj() != null) order.setHasBaggage(dto.getServices().getBagaj());
            if (dto.getServices().getChildSeat() != null) order.setHasChildSeat(dto.getServices().getChildSeat());
            if (dto.getServices().getTirkama() != null) order.setHasTrailer(dto.getServices().getTirkama());
        }

        return orderRepository.save(order);
    }

    public List<Order> getPendingOrders() {
        User currentUser = securityUtils.getCurrentUser();
        List<Order> orders;
        
        if (currentUser != null && currentUser.getRole() == User.Role.DRIVER) {
            // Drivers see passenger requests
            orders = orderRepository.findByStatusAndDriverIsNull(Order.OrderStatus.PENDING);
        } else {
            // Passengers see driver ride offers
            orders = orderRepository.findByStatusAndPassengerIsNull(Order.OrderStatus.PENDING);
        }

        // Auto-populate car info if missing in User but present in DriverProfile
        for (Order order : orders) {
            if (order.getDriver() != null) {
                User driver = order.getDriver();
                if (driver.getCarNumber() == null || driver.getCarModel() == null) {
                    driverProfileRepository.findByUser(driver).ifPresent(profile -> {
                        if (driver.getCarNumber() == null) driver.setCarNumber(profile.getCarNumber());
                        if (driver.getCarModel() == null) driver.setCarModel(profile.getCarModel());
                    });
                }
            }
        }
        
        return orders;
    }

    @Transactional
    public Order bookRide(Long orderId, List<String> selectedSeats) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can request to join ride offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        // Create a new RideBooking
        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(selectedSeats)
                .status("PENDING")
                .build();

        rideBookingRepository.save(booking);

        // Force Eager load by adding to bookings list
        order.getBookings().add(booking);
        
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order confirmBooking(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can confirm bookings");
        }

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        Order order = booking.getOrder();
        if (!order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        booking.setStatus("ACCEPTED");
        rideBookingRepository.save(booking);

        // Remove the selected seats from the availableSeats list (thus booking/occupying them)
        if (order.getAvailableSeats() != null) {
            for (String seat : booking.getSelectedSeats()) {
                String mappedSeat = mapSeatIndexToLabel(seat);
                order.getAvailableSeats().remove(mappedSeat);
            }
        }

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order rejectBooking(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can reject bookings");
        }

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        Order order = booking.getOrder();
        if (!order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        // If the booking was previously ACCEPTED, we must free the seats!
        if ("ACCEPTED".equals(booking.getStatus())) {
            if (order.getAvailableSeats() != null) {
                for (String seat : booking.getSelectedSeats()) {
                    String mappedSeat = mapSeatIndexToLabel(seat);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }
            }
        }

        booking.setStatus("REJECTED");
        rideBookingRepository.save(booking);

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    private String mapSeatIndexToLabel(String index) {
        if (index == null) return "";
        switch (index) {
            case "1": return "FRONT";
            case "2": return "BACK_LEFT";
            case "3": return "BACK_CENTER";
            case "4": return "BACK_RIGHT";
            default: return index;
        }
    }
}
