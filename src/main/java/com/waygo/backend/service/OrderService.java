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
            if (order.getPassenger() != null) {
                // Driver is cancelling their acceptance of a passenger's order!
                // We release it back to PENDING so another driver can accept it.
                order.setStatus(Order.OrderStatus.PENDING);
                order.setDriver(null);
                
                Order savedOrder = orderRepository.save(order);
                notificationService.notifyOrderStatusUpdate(savedOrder);
                notificationService.notifyNewOrder(savedOrder);
                return savedOrder;
            } else {
                // Driver is cancelling their own ride offer (e'lon).
                // We simply set it to CANCELLED and notify the passengers who booked it.
                order.setStatus(Order.OrderStatus.CANCELLED);
                Order savedOrder = orderRepository.save(order);
                notificationService.notifyOrderStatusUpdate(savedOrder);
                return savedOrder;
            }
        }

        if (status == Order.OrderStatus.STARTED) {
            if (order.getAvailableSeats() != null) {
                order.getAvailableSeats().clear();
            }
        } else if (status == Order.OrderStatus.PENDING) {
            List<String> allSeats = new java.util.ArrayList<>(java.util.Arrays.asList("FRONT", "BACK_LEFT", "BACK_CENTER", "BACK_RIGHT"));
            if (order.getBookings() != null) {
                for (com.waygo.backend.entity.RideBooking b : order.getBookings()) {
                    if ("ACCEPTED".equals(b.getStatus())) {
                        for (String seat : b.getSelectedSeats()) {
                            String mappedSeat = mapSeatIndexToLabel(seat);
                            allSeats.remove(mappedSeat);
                        }
                    }
                }
            }
            order.setAvailableSeats(allSeats);
        }

        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    public List<Order> getPassengerHistory(Long passengerId) {
        return orderRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId);
    }

    public List<com.waygo.backend.entity.RideBooking> getMyBookings() {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedAccessException("Not authenticated");
        }
        return rideBookingRepository.findByPassengerId(currentUser.getId());
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

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        
        // Re-announce driver offers so passengers receive the "Yangi haydovchi e'loni!" push notification
        if (savedOrder.getDriver() != null && savedOrder.getPassenger() == null) {
            notificationService.notifyNewOrder(savedOrder);
        }
        
        return savedOrder;
    }

    public List<Order> getPendingOrders() {
        User currentUser = securityUtils.getCurrentUser();
        List<Order> orders;
        
        if (currentUser != null && currentUser.getRole() == User.Role.DRIVER) {
            // Drivers see passenger requests
            orders = orderRepository.findByStatusAndDriverIsNull(Order.OrderStatus.PENDING);
        } else {
            // Passengers see driver ride offers (and started ones where they are accepted)
            if (currentUser != null) {
                orders = orderRepository.findPendingAndActiveForPassenger(currentUser.getId(), Order.OrderStatus.PENDING, Order.OrderStatus.STARTED);
            } else {
                orders = orderRepository.findByStatusAndPassengerIsNull(Order.OrderStatus.PENDING);
            }
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

        // Check if passenger already has a PENDING booking on this order — block duplicates
        java.util.Optional<com.waygo.backend.entity.RideBooking> pendingBooking =
                rideBookingRepository.findFirstByOrderIdAndPassengerIdAndStatus(orderId, passenger.getId(), "PENDING");
        if (pendingBooking.isPresent()) {
            throw new IllegalStateException("Siz allaqachon ushbu buyurtmaga so'rov yuborgansiz. Avvalgi so'rovingiz ko'rib chiqilguncha kuting.");
        }

        // Validate: all requested seats must be in the order's available seats list
        List<String> availableSeats = order.getAvailableSeats();
        if (availableSeats == null) {
            throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
        }
        for (String seat : selectedSeats) {
            String mappedSeat = mapSeatIndexToLabel(seat);
            if (!availableSeats.contains(mappedSeat)) {
                throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
            }
        }

        // Create a new RideBooking (works for both first-time and additional seat requests)
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
    public Order rejectBooking(Long bookingId, String seat) {
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

        if (seat != null && !seat.isEmpty()) {
            if (booking.getSelectedSeats().contains(seat)) {
                booking.getSelectedSeats().remove(seat);
                
                // If the booking was previously ACCEPTED, we must free the seat
                if ("ACCEPTED".equals(booking.getStatus())) {
                    if (order.getAvailableSeats() != null) {
                        String mappedSeat = mapSeatIndexToLabel(seat);
                        if (!order.getAvailableSeats().contains(mappedSeat)) {
                            order.getAvailableSeats().add(mappedSeat);
                        }
                    }
                }
                
                if (booking.getSelectedSeats().isEmpty()) {
                    booking.setStatus("REJECTED");
                }
                rideBookingRepository.save(booking);
            }
        } else {
            // If the booking was previously ACCEPTED, we must free the seats!
            if ("ACCEPTED".equals(booking.getStatus())) {
                if (order.getAvailableSeats() != null) {
                    for (String s : booking.getSelectedSeats()) {
                        String mappedSeat = mapSeatIndexToLabel(s);
                        if (!order.getAvailableSeats().contains(mappedSeat)) {
                            order.getAvailableSeats().add(mappedSeat);
                        }
                    }
                }
            }
            booking.setStatus("REJECTED");
            rideBookingRepository.save(booking);
        }

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order cancelBooking(Long bookingId, String seat) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can cancel bookings");
        }

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        if (!booking.getPassenger().getId().equals(passenger.getId())) {
            throw new UnauthorizedAccessException("You can only cancel your own bookings");
        }

        Order order = booking.getOrder();

        if (seat != null && !seat.isEmpty()) {
            if (booking.getSelectedSeats().contains(seat)) {
                booking.getSelectedSeats().remove(seat);
                
                if ("ACCEPTED".equals(booking.getStatus()) && order.getAvailableSeats() != null) {
                    String mappedSeat = mapSeatIndexToLabel(seat);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }
                
                if (booking.getSelectedSeats().isEmpty()) {
                    order.getBookings().remove(booking);
                    rideBookingRepository.delete(booking);
                } else {
                    rideBookingRepository.save(booking);
                }
            }
        } else {
            // If the booking was previously ACCEPTED, we must free the seats
            if ("ACCEPTED".equals(booking.getStatus()) && order.getAvailableSeats() != null) {
                for (String s : booking.getSelectedSeats()) {
                    String mappedSeat = mapSeatIndexToLabel(s);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }
            }
            order.getBookings().remove(booking);
            rideBookingRepository.delete(booking);
        }

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
