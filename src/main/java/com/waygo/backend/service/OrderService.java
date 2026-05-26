package com.waygo.backend.service;

import com.waygo.backend.dto.order.OrderCreateDTO;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.User;
import com.waygo.backend.entity.DriverOffer;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.exception.UnauthorizedAccessException;
import com.waygo.backend.repository.DriverProfileRepository;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

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
    public Order lockOrder(Long orderId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can lock orders");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("Faqat pending holatidagi buyurtmalarni band qilish mumkin");
        }

        if (order.getLockedByDriverId() != null && !order.getLockedByDriverId().equals(driver.getId())) {
            if (order.getLockExpirationTime() != null && order.getLockExpirationTime().isAfter(LocalDateTime.now())) {
                throw new IllegalStateException("Buyurtma ayni paytda boshqa haydovchi tomonidan ko'rib chiqilmoqda");
            }
        }

        order.setLockedByDriverId(driver.getId());
        order.setLockExpirationTime(LocalDateTime.now().plusSeconds(30));

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order unlockOrder(Long orderId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can unlock orders");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getLockedByDriverId() != null && order.getLockedByDriverId().equals(driver.getId())) {
            order.setLockedByDriverId(null);
            order.setLockExpirationTime(null);
            Order savedOrder = orderRepository.save(order);
            notificationService.notifyOrderStatusUpdate(savedOrder);
            notificationService.notifyNewOrder(savedOrder); // Notify as new so it reappears immediately
            return savedOrder;
        }

        return order;
    }

    @Transactional
    public Order acceptOrder(Long orderId, java.util.List<String> availableSeats) {
        return acceptOrder(orderId, availableSeats, null);
    }

    @Transactional
    public Order acceptOrder(Long orderId, java.util.List<String> availableSeats, java.math.BigDecimal pricePerPerson) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can accept orders");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getPassenger() == null) {
            throw new IllegalStateException("This is not a passenger request order");
        }

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("Order is no longer pending");
        }

        // Find if this driver already made an offer on this order
        DriverOffer offer = order.getDriverOffers().stream()
                .filter(o -> o.getDriver().getId().equals(driver.getId()))
                .findFirst()
                .orElse(null);

        if (offer == null) {
            offer = new DriverOffer();
            offer.setOrder(order);
            offer.setDriver(driver);
            order.getDriverOffers().add(offer);
        }

        offer.setStatus("PENDING");
        
        // Custom price offered by driver
        if (pricePerPerson != null) {
            offer.setPricePerPerson(pricePerPerson);
        } else {
            offer.setPricePerPerson(order.getPrice());
        }

        // Auto-calculate available seats based on driver's other active/accepted orders on the same route and departure date
        java.util.List<String> calculatedAvailableSeats = new java.util.ArrayList<>(java.util.Arrays.asList("FRONT", "BACK_LEFT", "BACK_CENTER", "BACK_RIGHT"));
        if (order.getDepartureDate() != null) {
            java.util.List<Order> otherDriverOrders = orderRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId());
            for (Order otherOrder : otherDriverOrders) {
                if (otherOrder.getStatus() != Order.OrderStatus.CANCELLED && 
                    otherOrder.getStatus() != Order.OrderStatus.COMPLETED && 
                    order.getDepartureDate().equals(otherOrder.getDepartureDate()) &&
                    (order.getFromAddress() == null || otherOrder.getFromAddress() == null ||
                     order.getFromAddress().substring(0, Math.min(order.getFromAddress().length(), 4))
                     .equalsIgnoreCase(otherOrder.getFromAddress().substring(0, Math.min(otherOrder.getFromAddress().length(), 4))))) {
                    
                    // Exclude seats that are already booked in this overlapping trip
                    for (com.waygo.backend.entity.RideBooking booking : otherOrder.getBookings()) {
                        if ("ACCEPTED".equalsIgnoreCase(booking.getStatus())) {
                            for (String seatNum : booking.getSelectedSeats()) {
                                String seatLabel = seatNum.equals("1") ? "FRONT"
                                        : seatNum.equals("2") ? "BACK_LEFT"
                                        : seatNum.equals("3") ? "BACK_CENTER"
                                        : seatNum.equals("4") ? "BACK_RIGHT"
                                        : "";
                                if (!seatLabel.isEmpty()) {
                                    calculatedAvailableSeats.remove(seatLabel);
                                }
                            }
                        }
                    }
                }
            }
        }
        offer.setAvailableSeats(calculatedAvailableSeats);

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order confirmDriver(Long orderId) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can confirm driver offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getPassenger() == null || !order.getPassenger().getId().equals(passenger.getId())) {
            throw new UnauthorizedAccessException("You can only confirm driver offers for your own requests");
        }

        if (order.getStatus() != Order.OrderStatus.ACCEPTED || order.getDriver() == null) {
            throw new IllegalStateException("Order is not in a state to be confirmed");
        }

        order.setPassengerConfirmed(true);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order rejectDriver(Long orderId) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can reject driver offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getPassenger() == null || !order.getPassenger().getId().equals(passenger.getId())) {
            throw new UnauthorizedAccessException("You can only reject driver offers for your own requests");
        }

        if (order.getStatus() != Order.OrderStatus.ACCEPTED || order.getDriver() == null) {
            throw new IllegalStateException("Order is not in a state to be rejected");
        }

        order.setDriver(null);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setPassengerConfirmed(false);
        if (order.getAvailableSeats() != null) {
            order.getAvailableSeats().clear();
        }

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        notificationService.notifyNewOrder(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order confirmDriverOffer(Long orderId, Long offerId, List<String> selectedSeats) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can confirm driver offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getPassenger() == null || !order.getPassenger().getId().equals(passenger.getId())) {
            throw new UnauthorizedAccessException("You can only confirm driver offers for your own requests");
        }

        DriverOffer chosenOffer = order.getDriverOffers().stream()
                .filter(o -> o.getId().equals(offerId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Driver offer not found with id: " + offerId));

        if (!chosenOffer.getStatus().equals("PENDING")) {
            throw new IllegalStateException("Offer is not in a state to be confirmed");
        }

        // Establish contract
        order.setDriver(chosenOffer.getDriver());
        order.setPrice(chosenOffer.getPricePerPerson());
        order.setAvailableSeats(new java.util.ArrayList<>(chosenOffer.getAvailableSeats()));
        order.setStatus(Order.OrderStatus.ACCEPTED);
        order.setPassengerConfirmed(true);

        // Mark the chosen offer as ACCEPTED and others as REJECTED
        for (DriverOffer offer : order.getDriverOffers()) {
            if (offer.getId().equals(offerId)) {
                offer.setStatus("ACCEPTED");
            } else {
                offer.setStatus("REJECTED");
            }
        }

        // Create a RideBooking to reserve the seats for this passenger
        if (selectedSeats == null || selectedSeats.isEmpty()) {
            throw new IllegalArgumentException("You must select which seats to book");
        }
        
        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(new java.util.ArrayList<>(selectedSeats))
                .status("ACCEPTED")
                .createdAt(LocalDateTime.now())
                .build();
                
        order.getBookings().add(booking);

        // Update available seats: remove passenger selected seats from saloon
        for (String seatNum : selectedSeats) {
            String seatLabel = seatNum.equals("1") ? "FRONT"
                    : seatNum.equals("2") ? "BACK_LEFT"
                    : seatNum.equals("3") ? "BACK_CENTER"
                    : seatNum.equals("4") ? "BACK_RIGHT"
                    : "";
            if (!seatLabel.isEmpty()) {
                order.getAvailableSeats().remove(seatLabel);
            }
        }

        Order savedOrder = orderRepository.save(order);

        // --- Auto-create or Update driver's ride announcement ---
        try {
            User driver = chosenOffer.getDriver();
            Order activeAnnouncement = findActiveAnnouncementForRoute(
                driver.getId(),
                order.getDepartureDate(),
                order.getFromAddress(),
                order.getToAddress()
            );

            if (activeAnnouncement == null) {
                // Auto-create driver announcement (e'lon)
                Order.OrderBuilder builder = Order.builder()
                    .driver(driver)
                    .passenger(null) // driver announcement
                    .fromAddress(order.getFromAddress())
                    .toAddress(order.getToAddress())
                    .fromLat(order.getFromLat())
                    .fromLon(order.getFromLon())
                    .toLat(order.getToLat())
                    .toLon(order.getToLon())
                    .departureDate(order.getDepartureDate())
                    .departureTime(order.getDepartureTime())
                    .price(chosenOffer.getPricePerPerson())
                    .baggageDescription(order.getBaggageDescription())
                    .hasAirConditioning(order.getHasAirConditioning())
                    .hasBaggage(order.getHasBaggage())
                    .hasChildSeat(order.getHasChildSeat())
                    .hasTrailer(order.getHasTrailer())
                    .notes("Yo'lovchi shartnomasi asosida avtomatik yaratildi")
                    .status(Order.OrderStatus.PENDING);

                // Initialize available seats to the driver's offered seats
                builder.availableSeats(new java.util.ArrayList<>(chosenOffer.getAvailableSeats()));
                activeAnnouncement = builder.build();
                activeAnnouncement = orderRepository.save(activeAnnouncement);
            }

            // Create RideBooking in this active driver announcement
            com.waygo.backend.entity.RideBooking autoBooking = com.waygo.backend.entity.RideBooking.builder()
                    .order(activeAnnouncement)
                    .passenger(passenger)
                    .selectedSeats(new java.util.ArrayList<>(selectedSeats))
                    .status("ACCEPTED")
                    .pickupAddress(order.getFromAddress())
                    .createdAt(LocalDateTime.now())
                    .build();

            rideBookingRepository.save(autoBooking);
            activeAnnouncement.getBookings().add(autoBooking);

            // Remove seats from announcement's availableSeats
            if (activeAnnouncement.getAvailableSeats() != null) {
                for (String seatNum : selectedSeats) {
                    String seatLabel = seatNum.equals("1") ? "FRONT"
                            : seatNum.equals("2") ? "BACK_LEFT"
                            : seatNum.equals("3") ? "BACK_CENTER"
                            : seatNum.equals("4") ? "BACK_RIGHT"
                            : "";
                    if (!seatLabel.isEmpty()) {
                        activeAnnouncement.getAvailableSeats().remove(seatLabel);
                    }
                }
            }

            orderRepository.save(activeAnnouncement);
            notificationService.notifyNewOrder(activeAnnouncement);
            notificationService.notifyOrderStatusUpdate(activeAnnouncement);
        } catch (Exception e) {
            e.printStackTrace();
        }

        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order rejectDriverOffer(Long orderId, Long offerId) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || passenger.getRole() != User.Role.PASSENGER) {
            throw new UnauthorizedAccessException("Only passengers can reject driver offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getPassenger() == null || !order.getPassenger().getId().equals(passenger.getId())) {
            throw new UnauthorizedAccessException("You can only reject driver offers for your own requests");
        }

        DriverOffer chosenOffer = order.getDriverOffers().stream()
                .filter(o -> o.getId().equals(offerId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Driver offer not found with id: " + offerId));

        chosenOffer.setStatus("REJECTED");

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order cancelDriverOffer(Long orderId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can cancel their offers");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        DriverOffer offer = order.getDriverOffers().stream()
                .filter(o -> o.getDriver().getId().equals(driver.getId()) && "PENDING".equalsIgnoreCase(o.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No pending driver offer found for this driver"));

        offer.setStatus("REJECTED");

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }


    @Transactional
    public Order assignSeats(Long orderId, List<String> selectedSeats) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can assign seats");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getDriver() == null || !order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this order");
        }

        if (!Boolean.TRUE.equals(order.getPassengerConfirmed())) {
            throw new IllegalStateException("Passenger has not confirmed the driver yet");
        }

        if (selectedSeats == null || selectedSeats.isEmpty()) {
            throw new IllegalArgumentException("Seats must be selected");
        }

        if (selectedSeats.size() != order.getPassengerCount()) {
            throw new IllegalArgumentException("Selected seats count must match passenger count: " + order.getPassengerCount());
        }

        List<String> available = order.getAvailableSeats();
        if (available == null) {
            throw new IllegalStateException("No available seats in this order");
        }

        List<String> mappedSeats = new java.util.ArrayList<>();
        for (String seat : selectedSeats) {
            String mapped = mapSeatIndexToLabel(seat);
            if (!available.contains(mapped)) {
                throw new IllegalStateException("Seat is not available: " + mapped);
            }
            mappedSeats.add(mapped);
        }

        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(order.getPassenger())
                .selectedSeats(selectedSeats)
                .status("ACCEPTED")
                .pickupAddress(order.getFromAddress())
                .build();

        rideBookingRepository.save(booking);
        order.getBookings().add(booking);

        for (String mapped : mappedSeats) {
            available.remove(mapped);
        }

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
        // Get orders where this driver is assigned (both driver's own ride announcements
        // AND passenger requests where driver's offer was accepted)
        List<Order> byDriver = orderRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
        
        // Also include passenger orders where this driver submitted an accepted offer
        // (in case driver.id wasn't set correctly, fallback via driverOffers)
        List<Order> byOffer = orderRepository.findByAcceptedOfferDriverId(driverId);
        
        // Merge and deduplicate by order ID
        java.util.Map<Long, Order> merged = new java.util.LinkedHashMap<>();
        for (Order o : byDriver) merged.put(o.getId(), o);
        for (Order o : byOffer) merged.putIfAbsent(o.getId(), o);
        
        List<Order> result = new java.util.ArrayList<>(merged.values());
        result.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return result;
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

    private Order findActiveAnnouncementForRoute(Long driverId, String departureDate, String fromAddress, String toAddress) {
        if (departureDate == null || fromAddress == null || toAddress == null) {
            return null;
        }

        List<Order> activeOrders = orderRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
        if (activeOrders == null) {
            return null;
        }
        for (Order other : activeOrders) {
            if (other.getPassenger() == null && // driver ride announcement
                other.getStatus() != Order.OrderStatus.CANCELLED &&
                other.getStatus() != Order.OrderStatus.COMPLETED &&
                departureDate.equals(other.getDepartureDate())) {

                // Compare routes
                if (isRouteMatching(fromAddress, other.getFromAddress()) && 
                    isRouteMatching(toAddress, other.getToAddress())) {
                    return other;
                }
            }
        }
        return null;
    }

    private boolean isRouteMatching(String addr1, String addr2) {
        if (addr1 == null || addr2 == null) return false;
        String clean1 = addr1.split(",")[0].trim().toLowerCase();
        String clean2 = addr2.split(",")[0].trim().toLowerCase();
        if (clean1.isEmpty() || clean2.isEmpty()) return false;
        if (clean1.equals(clean2)) return true;

        String prefix1 = clean1.substring(0, Math.min(clean1.length(), 4));
        String prefix2 = clean2.substring(0, Math.min(clean2.length(), 4));
        return prefix1.equalsIgnoreCase(prefix2);
    }

    public List<Order> getPendingOrders() {
        return getPendingOrders(null);
    }

    public List<Order> getPendingOrders(String region) {
        User currentUser = securityUtils.getCurrentUser();
        List<Order> orders;
        
        if (currentUser != null && currentUser.getRole() == User.Role.DRIVER) {
            // Drivers see passenger requests
            List<Order> rawOrders = orderRepository.findByStatusAndDriverIsNull(Order.OrderStatus.PENDING);
            orders = new java.util.ArrayList<>();
            for (Order o : rawOrders) {
                if (o.getLockedByDriverId() != null && !o.getLockedByDriverId().equals(currentUser.getId())) {
                    if (o.getLockExpirationTime() != null && o.getLockExpirationTime().isAfter(LocalDateTime.now())) {
                        continue; // Locked by another driver! Skip.
                    }
                }

                // Filter out orders where the current driver has a non-PENDING offer (e.g. REJECTED)
                boolean hasRejectedOffer = o.getDriverOffers().stream()
                        .anyMatch(offer -> offer.getDriver().getId().equals(currentUser.getId()) && 
                                           !"PENDING".equalsIgnoreCase(offer.getStatus()));
                if (hasRejectedOffer) {
                    continue; // Skip because driver's offer was rejected
                }

                // Find driver's active announcement on the same route and date
                Order matchingAnnouncement = findActiveAnnouncementForRoute(
                    currentUser.getId(),
                    o.getDepartureDate(),
                    o.getFromAddress(),
                    o.getToAddress()
                );

                int emptySeats = 4;
                if (matchingAnnouncement != null) {
                    emptySeats = matchingAnnouncement.getAvailableSeats() != null ? matchingAnnouncement.getAvailableSeats().size() : 0;
                }
                
                int requestedCount = o.getPassengerCount() != null ? o.getPassengerCount() : 1;
                if (requestedCount <= emptySeats) {
                    orders.add(o);
                }
            }
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

        // Filter by region if provided
        if (region != null && !region.trim().isEmpty() && !"Barchasi".equalsIgnoreCase(region.trim())) {
            List<Order> filtered = new java.util.ArrayList<>();
            for (Order order : orders) {
                if (order.getFromAddress() != null && 
                    order.getFromAddress().toLowerCase().contains(region.toLowerCase().trim())) {
                    filtered.add(order);
                }
            }
            return filtered;
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

        String notes = "";
        String pickup = "";
        List<String> seatsToBook = new java.util.ArrayList<>();
        for (String seat : selectedSeats) {
            if (seat != null && seat.startsWith("PICKUP:")) {
                pickup = seat.substring(7);
            } else if (seat != null && seat.startsWith("NOTES:")) {
                notes = seat.substring(6);
            } else {
                seatsToBook.add(seat);
            }
        }
        if (pickup.isEmpty() && order.getFromAddress() != null) {
            pickup = order.getFromAddress();
        }

        // Check if passenger already has active/pending bookings on this order
        List<com.waygo.backend.entity.RideBooking> existingBookings = rideBookingRepository.findByOrderIdAndPassengerId(orderId, passenger.getId());
        if (!existingBookings.isEmpty()) {
            boolean hasNewSeats = false;
            java.util.Set<String> currentlyBookedSeats = new java.util.HashSet<>();
            for (com.waygo.backend.entity.RideBooking b : existingBookings) {
                if (!"REJECTED".equals(b.getStatus())) {
                    currentlyBookedSeats.addAll(b.getSelectedSeats());
                }
            }

            for (String seat : seatsToBook) {
                if (!currentlyBookedSeats.contains(seat)) {
                    hasNewSeats = true;
                    break;
                }
            }

            // If no new seats are requested and we have a pickup address, update existing non-rejected bookings
            if (!hasNewSeats && !pickup.isEmpty()) {
                for (com.waygo.backend.entity.RideBooking b : existingBookings) {
                    if (!"REJECTED".equals(b.getStatus())) {
                        b.setPickupAddress(pickup);
                        if (!notes.isEmpty()) {
                            b.setNotes(notes);
                        }
                        rideBookingRepository.save(b);
                    }
                }
                Order savedOrder = orderRepository.save(order);
                notificationService.notifyOrderStatusUpdate(savedOrder);
                return savedOrder;
            }
        }

        // Check if passenger already has a PENDING booking on this order — block duplicates unless we're just updating pickupAddress
        java.util.Optional<com.waygo.backend.entity.RideBooking> pendingBooking =
                rideBookingRepository.findFirstByOrderIdAndPassengerIdAndStatus(orderId, passenger.getId(), "PENDING");
        if (pendingBooking.isPresent()) {
            if (!pickup.isEmpty()) {
                com.waygo.backend.entity.RideBooking b = pendingBooking.get();
                b.setPickupAddress(pickup);
                if (!notes.isEmpty()) {
                    b.setNotes(notes);
                }
                rideBookingRepository.save(b);
                Order savedOrder = orderRepository.save(order);
                notificationService.notifyOrderStatusUpdate(savedOrder);
                return savedOrder;
            }
            throw new IllegalStateException("Siz allaqachon ushbu buyurtmaga so'rov yuborgansiz. Avvalgi so'rovingiz ko'rib chiqilguncha kuting.");
        }

        // Validate: all requested seats must be in the order's available seats list
        List<String> availableSeats = order.getAvailableSeats();
        if (availableSeats == null) {
            throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
        }
        for (String seat : seatsToBook) {
            String mappedSeat = mapSeatIndexToLabel(seat);
            if (!availableSeats.contains(mappedSeat)) {
                throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
            }
        }

        // Create a new RideBooking (works for both first-time and additional seat requests)
        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(seatsToBook)
                .pickupAddress(pickup)
                .notes(notes)
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
