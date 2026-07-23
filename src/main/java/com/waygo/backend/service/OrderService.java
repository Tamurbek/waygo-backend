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
    private final com.waygo.backend.repository.UserRepository userRepository;
    private final com.waygo.backend.service.ReferralService referralService;

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
                .availableSeats(dto.getAvailableSeats() != null ? dto.getAvailableSeats() : new java.util.ArrayList<>())
                .passengerCount(dto.getPassengerCount())
                .notes(dto.getNotes())
                .price(dto.getPrice())
                .baggageDescription(dto.getBaggageDescription())
                .selectedServices(dto.getSelectedServices() != null ? dto.getSelectedServices() : new java.util.ArrayList<>())
                .status(Order.OrderStatus.PENDING);

        if (currentUser.getRole() == User.Role.DRIVER) {
            checkDriverBilling(currentUser);
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
        checkDriverBilling(driver);

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
        checkDriverBilling(driver);

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
        checkDriverBilling(driver);

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

        int requestedCount = order.getPassengerCount() != null ? order.getPassengerCount() : 1;
        if (calculatedAvailableSeats.size() < requestedCount) {
            throw new IllegalStateException("Sizda ushbu buyurtmani qabul qilish uchun yetarli bo'sh joy yo'q (Yo'lovchi so'ragan joylar: " + requestedCount + ", Sizdagi bo'sh joylar: " + calculatedAvailableSeats.size() + ")");
        }

        offer.setAvailableSeats(calculatedAvailableSeats);

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order confirmDriver(Long orderId) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
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
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
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
        order.setLockedByDriverId(null);
        order.setLockExpirationTime(null);
        if (order.getAvailableSeats() != null) {
            order.getAvailableSeats().clear();
        }

        // Clean up bookings using the passenger request order ID
        try {
            List<com.waygo.backend.entity.RideBooking> bookings = rideBookingRepository.findByPassengerOrderId(order.getId());
            for (com.waygo.backend.entity.RideBooking booking : bookings) {
                Order bookingOrder = booking.getOrder();
                if (bookingOrder != null) {
                    if (bookingOrder.getId().equals(order.getId())) {
                        bookingOrder.getBookings().remove(booking);
                        rideBookingRepository.delete(booking);
                    } else if (bookingOrder.getPassenger() == null) { // Driver announcement
                        if (bookingOrder.getAvailableSeats() != null) {
                            for (String s : booking.getSelectedSeats()) {
                                String mappedSeat = mapSeatIndexToLabel(s);
                                if (!bookingOrder.getAvailableSeats().contains(mappedSeat)) {
                                    bookingOrder.getAvailableSeats().add(mappedSeat);
                                }
                            }
                        }
                        bookingOrder.getBookings().remove(booking);
                        rideBookingRepository.delete(booking);
                        orderRepository.save(bookingOrder);
                        notificationService.notifyOrderStatusUpdate(bookingOrder);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        notificationService.notifyNewOrder(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order confirmDriverOffer(Long orderId, Long offerId, List<String> selectedSeats) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
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

        // Parse custom pickup and notes from selectedSeats
        String notes = "";
        String pickup = "";
        List<String> seatsToBook = new java.util.ArrayList<>();
        for (String seat : selectedSeats) {
            if (seat != null && seat.startsWith("PICKUP:")) {
                pickup = seat.substring(7);
            } else if (seat != null && seat.startsWith("NOTES:")) {
                notes = seat.substring(6);
            } else if (seat != null) {
                seatsToBook.add(seat);
            }
        }

        if (seatsToBook.isEmpty()) {
            throw new IllegalArgumentException("You must select which seats to book");
        }

        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(seatsToBook)
                .status("ACCEPTED")
                .passengerOrderId(order.getId())
                .pickupAddress(resolvePickupAddress(order, pickup))
                .notes(notes)
                .createdAt(LocalDateTime.now())
                .build();

        order.getBookings().add(booking);

        // Update available seats: remove passenger selected seats from saloon
        for (String seatNum : seatsToBook) {
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
                    .selectedServices(new java.util.ArrayList<>(order.getSelectedServices()))
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
                    .selectedSeats(seatsToBook)
                    .status("ACCEPTED")
                    .passengerOrderId(order.getId())
                    .pickupAddress(resolvePickupAddress(order, pickup))
                    .notes(notes)
                    .createdAt(LocalDateTime.now())
                    .build();

            rideBookingRepository.save(autoBooking);
            activeAnnouncement.getBookings().add(autoBooking);

            // Remove seats from announcement's availableSeats
            if (activeAnnouncement.getAvailableSeats() != null) {
                for (String seatNum : seatsToBook) {
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
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
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
        checkDriverBilling(driver);

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
        checkDriverBilling(driver);

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
                .pickupAddress(resolvePickupAddress(order, ""))
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
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
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
        if (currentUser.getRole() == User.Role.DRIVER) {
            checkDriverBilling(currentUser);
        }

        if (order.getStatus() != Order.OrderStatus.STARTED && order.getStatus() != Order.OrderStatus.ACCEPTED) {
            throw new IllegalStateException("Trip must be accepted or started to be completed");
        }

        // Process final payment automatically if passenger exists
        if (order.getPassenger() != null) {
            transactionService.processPayment(order.getPassenger().getId(), order.getDriver().getId(), order.getPrice());
            // referralService.rewardInviterIfFirstTripCompleted(order.getPassenger());
        } else if (order.getBookings() != null) {
            // Also process payments for all accepted bookings on this driver announcement
            for (com.waygo.backend.entity.RideBooking booking : order.getBookings()) {
                if ("ACCEPTED".equalsIgnoreCase(booking.getStatus())) {
                    // Process payment for this booking
                    int seatsCount = booking.getSelectedSeats().size();
                    java.math.BigDecimal totalBookingPrice = order.getPrice().multiply(java.math.BigDecimal.valueOf(seatsCount));
                    transactionService.processPayment(booking.getPassenger().getId(), order.getDriver().getId(), totalBookingPrice);
                    // referralService.rewardInviterIfFirstTripCompleted(booking.getPassenger());

                    // Try to find the corresponding passenger request order and mark it as COMPLETED too
                    try {
                        if (booking.getPassengerOrderId() != null) {
                            orderRepository.findById(booking.getPassengerOrderId()).ifPresent(pOrder -> {
                                if (pOrder.getStatus() != Order.OrderStatus.COMPLETED && pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                                    pOrder.setStatus(Order.OrderStatus.COMPLETED);
                                    orderRepository.save(pOrder);
                                    notificationService.notifyOrderStatusUpdate(pOrder);
                                }
                            });
                        } else {
                            List<Order> passengerOrders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(booking.getPassenger().getId());
                            for (Order pOrder : passengerOrders) {
                                if (pOrder.getPassenger() != null &&
                                    pOrder.getDriver() != null &&
                                    pOrder.getDriver().getId().equals(order.getDriver().getId()) &&
                                    pOrder.getStatus() != Order.OrderStatus.COMPLETED &&
                                    pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                                    pOrder.setStatus(Order.OrderStatus.COMPLETED);
                                    orderRepository.save(pOrder);
                                    notificationService.notifyOrderStatusUpdate(pOrder);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        // Increment driver tripsCount on completion
        User driver = order.getDriver();
        if (driver != null) {
            int currentTrips = driver.getTripsCount() != null ? driver.getTripsCount() : 0;
            driver.setTripsCount(currentTrips + 1);
            userRepository.save(driver);
            
            if (currentTrips == 0) {
                // referralService.rewardInviterIfFirstTripCompleted(driver);
            }
        }

        order.setStatus(Order.OrderStatus.COMPLETED);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order rateDriver(Long orderId, Double rating, String comment) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
        User driver = order.getDriver();
        if (driver == null) {
            throw new IllegalStateException("No driver is assigned to this order");
        }

        double currentRating = driver.getRating() != null ? driver.getRating() : 5.0;
        int currentTrips = driver.getTripsCount() != null ? driver.getTripsCount() : 0;
        if (currentTrips == 0) {
            currentTrips = 1;
        }

        double updatedRating = ((currentRating * (currentTrips - 1)) + rating) / currentTrips;
        updatedRating = Math.max(1.0, Math.min(5.0, updatedRating));

        driver.setRating(updatedRating);
        userRepository.save(driver);

        order.setRating(rating);
        order.setComment(comment);
        Order savedOrder = orderRepository.save(order);

        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser != null && currentUser.getRole() == User.Role.DRIVER) {
            checkDriverBilling(currentUser);
        }
        boolean isPassenger = currentUser != null && order.getPassenger() != null && currentUser.getId().equals(order.getPassenger().getId());
        boolean isDriver = currentUser != null && order.getDriver() != null && currentUser.getId().equals(order.getDriver().getId());

        if (!isPassenger && !isDriver) {
            throw new UnauthorizedAccessException("You are not part of this order");
        }

        if (status == Order.OrderStatus.CANCELLED && isPassenger) {
            // Passenger is cancelling their request.
            // 1. Find all bookings linked to this order and clean them up.
            try {
                List<com.waygo.backend.entity.RideBooking> linkedBookings = rideBookingRepository.findByPassengerOrderId(order.getId());
                for (com.waygo.backend.entity.RideBooking booking : linkedBookings) {
                    Order driverOrder = booking.getOrder();
                    if (driverOrder != null) {
                        // Free seats in driver's order if the booking was accepted
                        if ("ACCEPTED".equals(booking.getStatus()) && driverOrder.getAvailableSeats() != null) {
                            for (String seat : booking.getSelectedSeats()) {
                                String mappedSeat = mapSeatIndexToLabel(seat);
                                if (!driverOrder.getAvailableSeats().contains(mappedSeat)) {
                                    driverOrder.getAvailableSeats().add(mappedSeat);
                                }
                            }
                        }
                        driverOrder.getBookings().remove(booking);
                        orderRepository.save(driverOrder);
                        notificationService.notifyOrderStatusUpdate(driverOrder);
                    }
                    rideBookingRepository.delete(booking);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            // 2. Notify the driver if they were already assigned
            if (order.getDriver() != null) {
                notificationService.notifyDriverOrderCancelledByPassenger(order);
            }
        }

        if (status == Order.OrderStatus.CANCELLED && isDriver) {
            if (order.getPassenger() != null) {
                // Driver is cancelling their acceptance of a passenger's order!
                // We release it back to PENDING so another driver can accept it.
                order.setStatus(Order.OrderStatus.PENDING);
                order.setDriver(null);
                order.setLockedByDriverId(null);
                order.setLockExpirationTime(null);

                Order savedOrder = orderRepository.save(order);
                notificationService.notifyOrderStatusUpdate(savedOrder);
                notificationService.notifyNewOrder(savedOrder);
                return savedOrder;
            } else {
                // Driver is cancelling their own ride offer (e'lon).
                // We simply set it to CANCELLED and notify the passengers who booked it.
                order.setStatus(Order.OrderStatus.CANCELLED);

                // Release/Cancel passenger bookings and orders!
                if (order.getBookings() != null) {
                    for (com.waygo.backend.entity.RideBooking booking : order.getBookings()) {
                        boolean isConfirmed = "ACCEPTED".equals(booking.getStatus());
                        booking.setStatus("REJECTED"); // Cancel booking
                        try {
                            if (booking.getPassengerOrderId() != null) {
                                orderRepository.findById(booking.getPassengerOrderId()).ifPresent(pOrder -> {
                                    if (pOrder.getStatus() == Order.OrderStatus.ACCEPTED) {
                                        pOrder.setStatus(Order.OrderStatus.CANCELLED);
                                        pOrder.setDriver(null);
                                        pOrder.setPassengerConfirmed(false);
                                        pOrder.setLockedByDriverId(null);
                                        pOrder.setLockExpirationTime(null);
                                        if (pOrder.getAvailableSeats() != null) {
                                            pOrder.getAvailableSeats().clear();
                                        }
                                        if (pOrder.getBookings() != null) {
                                            for (com.waygo.backend.entity.RideBooking pb : pOrder.getBookings()) {
                                                if (pb.getPassenger().getId().equals(booking.getPassenger().getId())) {
                                                    pb.setStatus("REJECTED");
                                                    rideBookingRepository.save(pb);
                                                }
                                            }
                                        }
                                        orderRepository.save(pOrder);
                                        if (isConfirmed) {
                                            notificationService.notifyPassengerOrderCancelledByDriver(pOrder, order);
                                        } else {
                                            notificationService.notifyOrderStatusUpdate(pOrder);
                                        }
                                    }
                                });
                            } else {
                                List<Order> passengerOrders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(booking.getPassenger().getId());
                                for (Order pOrder : passengerOrders) {
                                    if (pOrder.getPassenger() != null &&
                                        pOrder.getDriver() != null &&
                                        pOrder.getDriver().getId().equals(order.getDriver().getId()) &&
                                        pOrder.getStatus() == Order.OrderStatus.ACCEPTED) {
                                        pOrder.setStatus(Order.OrderStatus.CANCELLED);
                                        pOrder.setDriver(null);
                                        pOrder.setPassengerConfirmed(false);
                                        pOrder.setLockedByDriverId(null);
                                        pOrder.setLockExpirationTime(null);
                                        if (pOrder.getAvailableSeats() != null) {
                                            pOrder.getAvailableSeats().clear();
                                        }
                                        if (pOrder.getBookings() != null) {
                                            for (com.waygo.backend.entity.RideBooking pb : pOrder.getBookings()) {
                                                if (pb.getPassenger().getId().equals(booking.getPassenger().getId())) {
                                                    pb.setStatus("REJECTED");
                                                    rideBookingRepository.save(pb);
                                                }
                                            }
                                        }
                                        orderRepository.save(pOrder);
                                        if (isConfirmed) {
                                            notificationService.notifyPassengerOrderCancelledByDriver(pOrder, order);
                                        } else {
                                            notificationService.notifyOrderStatusUpdate(pOrder);
                                        }
                                    }
                                }
                                if (isConfirmed) {
                                    notificationService.notifyBookingCancelledByDriver(booking, order);
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }

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

        // Synchronize passenger request orders status if this is a driver announcement
        if (order.getPassenger() == null && (status == Order.OrderStatus.ARRIVED || status == Order.OrderStatus.STARTED)) {
            if (order.getBookings() != null) {
                for (com.waygo.backend.entity.RideBooking booking : order.getBookings()) {
                    if ("ACCEPTED".equalsIgnoreCase(booking.getStatus())) {
                        try {
                            if (booking.getPassengerOrderId() != null) {
                                orderRepository.findById(booking.getPassengerOrderId()).ifPresent(pOrder -> {
                                    if (pOrder.getStatus() != Order.OrderStatus.COMPLETED && pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                                        pOrder.setStatus(status);
                                        orderRepository.save(pOrder);
                                        notificationService.notifyOrderStatusUpdate(pOrder);
                                    }
                                });
                            } else {
                                List<Order> passengerOrders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(booking.getPassenger().getId());
                                for (Order pOrder : passengerOrders) {
                                    if (pOrder.getPassenger() != null &&
                                        pOrder.getDriver() != null &&
                                        pOrder.getDriver().getId().equals(order.getDriver().getId()) &&
                                        pOrder.getStatus() != Order.OrderStatus.COMPLETED &&
                                        pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                                        pOrder.setStatus(status);
                                        orderRepository.save(pOrder);
                                        notificationService.notifyOrderStatusUpdate(pOrder);
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }

        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    public List<Order> getPassengerHistory(Long passengerId, int page, int size) {
        List<Order> all = getPassengerHistory(passengerId);
        int start = Math.min(page * size, all.size());
        int end = Math.min(start + size, all.size());
        return all.subList(start, end);
    }

    public List<Order> getPassengerHistory(Long passengerId) {
        List<Order> rawOrders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId);

        // Find all passenger request order IDs owned by this passenger
        java.util.Set<Long> passengerOrderIds = new java.util.HashSet<>();
        for (Order o : rawOrders) {
            if (o.getPassenger() != null && o.getPassenger().getId().equals(passengerId)) {
                passengerOrderIds.add(o.getId());
            }
        }

        // Filter out driver announcements that are associated with these passenger request orders
        List<Order> filtered = new java.util.ArrayList<>();
        for (Order o : rawOrders) {
            if (o.getPassenger() == null) { // Driver announcement
                boolean isDuplicate = false;
                if (o.getBookings() != null) {
                    for (com.waygo.backend.entity.RideBooking booking : o.getBookings()) {
                        if (booking.getPassenger().getId().equals(passengerId) &&
                            booking.getPassengerOrderId() != null &&
                            passengerOrderIds.contains(booking.getPassengerOrderId())) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }
                if (isDuplicate) {
                    continue; // Skip this driver announcement to avoid duplicate cards
                }
            }
            filtered.add(o);
        }

        return filtered;
    }

    public List<com.waygo.backend.entity.RideBooking> getMyBookings() {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedAccessException("Not authenticated");
        }
        return rideBookingRepository.findByPassengerId(currentUser.getId());
    }

    public List<Order> getDriverHistory(Long driverId, int page, int size) {
        List<Order> all = getDriverHistory(driverId);
        int start = Math.min(page * size, all.size());
        int end = Math.min(start + size, all.size());
        return all.subList(start, end);
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

        // Filter out passenger requests that have been merged/converted into the driver's own active announcement
        List<Order> filteredResult = new java.util.ArrayList<>();
        for (Order o : result) {
            if (o.getPassenger() != null) {
                // Check if there is an active announcement for this driver on the same route and date
                Order announcement = findActiveAnnouncementForRoute(
                    driverId,
                    o.getDepartureDate(),
                    o.getFromAddress(),
                    o.getToAddress()
                );
                if (announcement != null) {
                    continue; // Skip this passenger request to avoid duplicate cards!
                }
            }
            filteredResult.add(o);
        }

        filteredResult.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return filteredResult;
    }

    @Transactional
    public Order updateOrder(Long orderId, OrderCreateDTO dto) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedAccessException("User not authenticated");
        }

        // Check if the order status allows editing (not started, completed, or cancelled)
        if (order.getStatus() == Order.OrderStatus.STARTED || 
            order.getStatus() == Order.OrderStatus.COMPLETED || 
            order.getStatus() == Order.OrderStatus.CANCELLED) {
            throw new IllegalStateException("You cannot edit this order after the trip has started or ended");
        }

        boolean isOwner = (order.getPassenger() != null && currentUser.getId().equals(order.getPassenger().getId())) ||
                         (order.getDriver() != null && currentUser.getId().equals(order.getDriver().getId()));

        if (!isOwner) {
            // Check if currentUser is a passenger in one of this order's bookings (shared ride)
            com.waygo.backend.entity.RideBooking userBooking = null;
            if (order.getBookings() != null) {
                for (com.waygo.backend.entity.RideBooking booking : order.getBookings()) {
                    if (booking.getPassenger() != null && currentUser.getId().equals(booking.getPassenger().getId())) {
                        userBooking = booking;
                        break;
                    }
                }
            }

            if (userBooking != null) {
                // Update booking's pickup address
                if (dto.getFromAddress() != null) {
                    String formattedPickupAddress = dto.getFromAddress();
                    if (dto.getFromLat() != null && dto.getFromLon() != null) {
                        formattedPickupAddress += " [LAT:" + dto.getFromLat() + ",LON:" + dto.getFromLon() + "]";
                    }
                    userBooking.setPickupAddress(formattedPickupAddress);
                }
                if (dto.getNotes() != null) {
                    userBooking.setNotes(dto.getNotes());
                }
                rideBookingRepository.save(userBooking);

                // Update passenger's virtual order
                if (userBooking.getPassengerOrderId() != null) {
                    Order passengerOrder = orderRepository.findById(userBooking.getPassengerOrderId()).orElse(null);
                    if (passengerOrder != null) {
                        if (dto.getFromAddress() != null) passengerOrder.setFromAddress(dto.getFromAddress());
                        if (dto.getFromLat() != null) passengerOrder.setFromLat(dto.getFromLat());
                        if (dto.getFromLon() != null) passengerOrder.setFromLon(dto.getFromLon());
                        if (dto.getToAddress() != null) passengerOrder.setToAddress(dto.getToAddress());
                        if (dto.getToLat() != null) passengerOrder.setToLat(dto.getToLat());
                        if (dto.getToLon() != null) passengerOrder.setToLon(dto.getToLon());
                        if (dto.getNotes() != null) passengerOrder.setNotes(dto.getNotes());
                        orderRepository.save(passengerOrder);
                    }
                }

                // Notify order update via WebSocket
                notificationService.notifyOrderStatusUpdate(order);

                // Return the updated driver order
                return orderRepository.save(order);
            } else {
                throw new UnauthorizedAccessException("You can only edit your own orders");
            }
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

        if (dto.getSelectedServices() != null) {
            if (order.getSelectedServices() == null) {
                order.setSelectedServices(new java.util.ArrayList<>());
            }
            order.getSelectedServices().clear();
            order.getSelectedServices().addAll(dto.getSelectedServices());
        }

        Order savedOrder = orderRepository.save(order);
        synchronizeAnnouncementToPassengerOrders(savedOrder);
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

    private Order findPendingPassengerRequestForRoute(Long passengerId, String departureDate, String fromAddress, String toAddress) {
        if (departureDate == null || fromAddress == null || toAddress == null) {
            return null;
        }

        List<Order> passengerOrders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId);
        if (passengerOrders == null) {
            return null;
        }
        for (Order other : passengerOrders) {
            if (other.getPassenger() != null && // passenger request order
                other.getStatus() == Order.OrderStatus.PENDING &&
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

                // Removed rejected offer filtering so orders can reappear for all drivers after cancellations

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
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
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

        // Check if passenger already has an ACCEPTED booking on this order — merge the requested seats to it!
        java.util.Optional<com.waygo.backend.entity.RideBooking> pendingBooking =
                rideBookingRepository.findFirstByOrderIdAndPassengerIdAndStatus(orderId, passenger.getId(), "ACCEPTED");
        if (pendingBooking.isPresent()) {
            com.waygo.backend.entity.RideBooking b = pendingBooking.get();
            // Merge seats
            for (String seat : seatsToBook) {
                if (!b.getSelectedSeats().contains(seat)) {
                    b.getSelectedSeats().add(seat);
                }
            }
            if (!pickup.isEmpty()) {
                b.setPickupAddress(pickup);
            }
            if (!notes.isEmpty()) {
                b.setNotes(notes);
            }
            rideBookingRepository.save(b);

            // Sync with driver's active announcement if present
            if (order.getPassenger() != null && order.getDriver() != null) {
                try {
                    User driver = order.getDriver();
                    Order activeAnnouncement = findActiveAnnouncementForRoute(
                        driver.getId(),
                        order.getDepartureDate(),
                        order.getFromAddress(),
                        order.getToAddress()
                    );
                    if (activeAnnouncement != null) {
                        java.util.Optional<com.waygo.backend.entity.RideBooking> driverPending =
                            rideBookingRepository.findFirstByOrderIdAndPassengerIdAndStatus(activeAnnouncement.getId(), passenger.getId(), "ACCEPTED");
                        if (driverPending.isPresent()) {
                            com.waygo.backend.entity.RideBooking db = driverPending.get();
                            for (String seat : seatsToBook) {
                                if (!db.getSelectedSeats().contains(seat)) {
                                    db.getSelectedSeats().add(seat);
                                }
                            }
                            if (!pickup.isEmpty()) {
                                db.setPickupAddress(pickup);
                            }
                            if (!notes.isEmpty()) {
                                db.setNotes(notes);
                            }
                            rideBookingRepository.save(db);
                            notificationService.notifyOrderStatusUpdate(activeAnnouncement);
                            notificationService.notifySeatBookedByPassenger(activeAnnouncement, passenger);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            Order savedOrder = orderRepository.save(order);
            notificationService.notifyOrderStatusUpdate(savedOrder);
            notificationService.notifySeatBookedByPassenger(savedOrder, passenger);
            return savedOrder;
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

        // Find if passenger has a matching pending request order
        Long passengerOrderId = null;
        Order matchingRequest = null;
        try {
            matchingRequest = findPendingPassengerRequestForRoute(
                passenger.getId(),
                order.getDepartureDate(),
                order.getFromAddress(),
                order.getToAddress()
            );
            if (matchingRequest != null) {
                passengerOrderId = matchingRequest.getId();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Create a new RideBooking (works for both first-time and additional seat requests)
        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(seatsToBook)
                .pickupAddress(resolvePickupAddress(matchingRequest != null ? matchingRequest : order, pickup))
                .notes(notes)
                .status("ACCEPTED")
                .passengerOrderId(passengerOrderId)
                .build();

        rideBookingRepository.save(booking);

        // Auto-occupy seats for the requested booking immediately
        if (order.getAvailableSeats() != null) {
            for (String seat : seatsToBook) {
                String mappedSeat = mapSeatIndexToLabel(seat);
                order.getAvailableSeats().remove(mappedSeat);
            }
        }

        // Force Eager load by adding to bookings list
        order.getBookings().add(booking);

        Order savedOrder = orderRepository.save(order);

        // Sync with driver's active announcement if present
        if (order.getPassenger() != null && order.getDriver() != null) {
            try {
                User driver = order.getDriver();
                Order activeAnnouncement = findActiveAnnouncementForRoute(
                    driver.getId(),
                    order.getDepartureDate(),
                    order.getFromAddress(),
                    order.getToAddress()
                );
                if (activeAnnouncement != null) {
                    boolean alreadyBooked = activeAnnouncement.getBookings().stream()
                        .anyMatch(b -> b.getPassenger().getId().equals(passenger.getId())
                                    && !"REJECTED".equals(b.getStatus())
                                    && b.getSelectedSeats().equals(seatsToBook));
                    if (!alreadyBooked) {
                        com.waygo.backend.entity.RideBooking autoBooking = com.waygo.backend.entity.RideBooking.builder()
                                .order(activeAnnouncement)
                                .passenger(passenger)
                                .selectedSeats(new java.util.ArrayList<>(seatsToBook))
                                .status("ACCEPTED")
                                .passengerOrderId(order.getId())
                                .pickupAddress(resolvePickupAddress(matchingRequest != null ? matchingRequest : order, pickup))
                                .notes(notes)
                                .createdAt(java.time.LocalDateTime.now())
                                .build();

                        rideBookingRepository.save(autoBooking);

                        // Auto-occupy seats for the active announcement immediately
                        if (activeAnnouncement.getAvailableSeats() != null) {
                            for (String seat : seatsToBook) {
                                String mappedSeat = mapSeatIndexToLabel(seat);
                                activeAnnouncement.getAvailableSeats().remove(mappedSeat);
                            }
                        }

                        activeAnnouncement.getBookings().add(autoBooking);
                        orderRepository.save(activeAnnouncement);
                        notificationService.notifyOrderStatusUpdate(activeAnnouncement);
                        notificationService.notifySeatBookedByPassenger(activeAnnouncement, passenger);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        notificationService.notifyOrderStatusUpdate(savedOrder);
        notificationService.notifySeatBookedByPassenger(savedOrder, passenger);
        return savedOrder;
    }

    @Transactional
    public Order confirmBooking(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can confirm bookings");
        }
        checkDriverBilling(driver);

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        Order order = booking.getOrder();
        if (!order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        booking.setStatus("ACCEPTED");
        rideBookingRepository.save(booking);

        // Remove the selected seats from the availableSeats list (thus booking/occupying them)
        // (Seats are already removed during bookRide to prevent race conditions)

        // Merge this booking with any other existing ACCEPTED booking for the same passenger under this order
        java.util.List<com.waygo.backend.entity.RideBooking> existingAcceptedBookings =
                rideBookingRepository.findByOrderIdAndPassengerId(order.getId(), booking.getPassenger().getId());

        com.waygo.backend.entity.RideBooking targetAcceptedBooking = null;
        for (com.waygo.backend.entity.RideBooking b : existingAcceptedBookings) {
            if ("ACCEPTED".equals(b.getStatus()) && !b.getId().equals(booking.getId())) {
                targetAcceptedBooking = b;
                break;
            }
        }

        if (targetAcceptedBooking != null) {
            // Merge seats
            for (String seat : booking.getSelectedSeats()) {
                if (!targetAcceptedBooking.getSelectedSeats().contains(seat)) {
                    targetAcceptedBooking.getSelectedSeats().add(seat);
                }
            }
            if (booking.getPickupAddress() != null && !booking.getPickupAddress().isEmpty()) {
                targetAcceptedBooking.setPickupAddress(booking.getPickupAddress());
            }
            if (booking.getNotes() != null && !booking.getNotes().isEmpty()) {
                targetAcceptedBooking.setNotes(booking.getNotes());
            }

            // Remove the merged booking from order's list & database
            order.getBookings().remove(booking);
            rideBookingRepository.delete(booking);

            // Save the merged target booking
            rideBookingRepository.save(targetAcceptedBooking);

            // Use the targetAcceptedBooking for the rest of the passenger contract logic
            booking = targetAcceptedBooking;
        }

        final com.waygo.backend.entity.RideBooking finalBooking = booking;

        // Link and update the passenger request order if passengerOrderId is set!
        if (finalBooking.getPassengerOrderId() != null) {
            try {
                orderRepository.findById(finalBooking.getPassengerOrderId()).ifPresent(pOrder -> {
                    if (pOrder.getStatus() == Order.OrderStatus.PENDING) {
                        // Cancel the passenger's own pending request order because they successfully joined a driver announcement!
                        pOrder.setStatus(Order.OrderStatus.CANCELLED);
                        orderRepository.save(pOrder);
                        notificationService.notifyOrderStatusUpdate(pOrder);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Order savedOrder = orderRepository.save(order);
        synchronizeAnnouncementToPassengerOrders(savedOrder);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        notificationService.notifyBookingConfirmed(finalBooking);
        return savedOrder;
    }

    @Transactional
    public Order collectBooking(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can collect passengers");
        }
        checkDriverBilling(driver);

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        Order order = booking.getOrder();
        if (order.getDriver() == null || !order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        booking.setStatus("COLLECTED");
        rideBookingRepository.save(booking);

        Order savedOrder = orderRepository.save(order);
        synchronizeAnnouncementToPassengerOrders(savedOrder);
        notificationService.notifyOrderStatusUpdate(savedOrder);

        // Find the next passenger in sequence and notify them that it is their turn
        if (savedOrder.getBookings() != null) {
            for (com.waygo.backend.entity.RideBooking b : savedOrder.getBookings()) {
                if (b != null && "ACCEPTED".equalsIgnoreCase(b.getStatus()) && b.getPassenger() != null) {
                    notificationService.notifyNextPassengerTurn(b.getPassenger(), driver);
                    break; // Notify the next passenger in sequence
                }
            }
        }

        return savedOrder;
    }

    @Transactional
    public Order uncollectBooking(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can uncollect passengers");
        }
        checkDriverBilling(driver);

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        Order order = booking.getOrder();
        if (order.getDriver() == null || !order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        booking.setStatus("ACCEPTED");
        rideBookingRepository.save(booking);

        Order savedOrder = orderRepository.save(order);
        synchronizeAnnouncementToPassengerOrders(savedOrder);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order rejectBooking(Long bookingId, String seat) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can reject bookings");
        }
        checkDriverBilling(driver);

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        Order order = booking.getOrder();
        if (!order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        boolean wasAccepted = "ACCEPTED".equals(booking.getStatus());

        if (seat != null && !seat.isEmpty()) {
            if (booking.getSelectedSeats().contains(seat)) {
                booking.getSelectedSeats().remove(seat);

                // Free the seat (auto-occupy policy)
                if (order.getAvailableSeats() != null) {
                    String mappedSeat = mapSeatIndexToLabel(seat);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }

                if (booking.getSelectedSeats().isEmpty()) {
                    booking.setStatus("REJECTED");
                }
                rideBookingRepository.save(booking);
            }
        } else {
            // Free the seats (auto-occupy policy)
            if (order.getAvailableSeats() != null) {
                for (String s : booking.getSelectedSeats()) {
                    String mappedSeat = mapSeatIndexToLabel(s);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }
            }
            booking.setStatus("REJECTED");
            rideBookingRepository.save(booking);
        }

        // Sync changes to the passenger request order (pOrder)
        try {
            Long pOrderId = booking.getPassengerOrderId();

            // Sync with other bookings sharing the same passengerOrderId (e.g. driver auto-created announcements)
            if (pOrderId != null) {
                java.util.List<com.waygo.backend.entity.RideBooking> relatedBookings = rideBookingRepository.findByPassengerOrderId(pOrderId);
                for (com.waygo.backend.entity.RideBooking rb : relatedBookings) {
                    if (!rb.getId().equals(booking.getId()) && !"REJECTED".equals(rb.getStatus())) {
                        if (seat != null && !seat.isEmpty()) {
                            if (rb.getSelectedSeats().contains(seat)) {
                                rb.getSelectedSeats().remove(seat);
                                // Free the seat
                                String mappedSeat = mapSeatIndexToLabel(seat);
                                Order rbOrder = rb.getOrder();
                                if (rbOrder != null && rbOrder.getAvailableSeats() != null) {
                                    if (!rbOrder.getAvailableSeats().contains(mappedSeat)) {
                                        rbOrder.getAvailableSeats().add(mappedSeat);
                                        orderRepository.save(rbOrder);
                                        notificationService.notifyOrderStatusUpdate(rbOrder);
                                    }
                                }
                                if (rb.getSelectedSeats().isEmpty()) {
                                    rb.setStatus("REJECTED");
                                }
                                rideBookingRepository.save(rb);
                            }
                        } else {
                            // Free the seats
                            Order rbOrder = rb.getOrder();
                            if (rbOrder != null && rbOrder.getAvailableSeats() != null) {
                                for (String s : rb.getSelectedSeats()) {
                                    String mappedSeat = mapSeatIndexToLabel(s);
                                    if (!rbOrder.getAvailableSeats().contains(mappedSeat)) {
                                        rbOrder.getAvailableSeats().add(mappedSeat);
                                    }
                                }
                                orderRepository.save(rbOrder);
                                notificationService.notifyOrderStatusUpdate(rbOrder);
                            }
                            rb.setStatus("REJECTED");
                            rideBookingRepository.save(rb);
                        }
                    }
                }
            }

            Order pOrder = null;
            if (pOrderId != null) {
                pOrder = orderRepository.findById(pOrderId).orElse(null);
            } else {
                // Fallback to route matching for legacy bookings
                User passenger = booking.getPassenger();
                if (passenger != null) {
                    java.util.List<Order> passengerOrders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId());
                    for (Order candidate : passengerOrders) {
                        if (candidate.getStatus() != Order.OrderStatus.COMPLETED
                                && candidate.getStatus() != Order.OrderStatus.CANCELLED
                                && candidate.getDriver() != null
                                && candidate.getDriver().getId().equals(driver.getId())
                                && candidate.getDepartureDate().equals(order.getDepartureDate())
                                && isRouteMatching(candidate.getFromAddress(), order.getFromAddress())
                                && isRouteMatching(candidate.getToAddress(), order.getToAddress())) {
                            pOrder = candidate;
                            break;
                        }
                    }
                }
            }

            if (pOrder != null) {
                // Find and update passenger's booking on pOrder
                if (pOrder.getBookings() != null) {
                    for (com.waygo.backend.entity.RideBooking pBooking : pOrder.getBookings()) {
                        if (pBooking.getPassenger() != null && pBooking.getPassenger().getId().equals(booking.getPassenger().getId())) {
                            if (seat != null && !seat.isEmpty()) {
                                if (pBooking.getSelectedSeats().contains(seat)) {
                                    pBooking.getSelectedSeats().remove(seat);
                                    if ("ACCEPTED".equals(pBooking.getStatus()) && pOrder.getAvailableSeats() != null) {
                                        String mappedSeat = mapSeatIndexToLabel(seat);
                                        if (!pOrder.getAvailableSeats().contains(mappedSeat)) {
                                            pOrder.getAvailableSeats().add(mappedSeat);
                                        }
                                    }
                                    if (pBooking.getSelectedSeats().isEmpty()) {
                                        pBooking.setStatus("REJECTED");
                                    }
                                    rideBookingRepository.save(pBooking);
                                }
                            } else {
                                if ("ACCEPTED".equals(pBooking.getStatus()) && pOrder.getAvailableSeats() != null) {
                                    for (String s : pBooking.getSelectedSeats()) {
                                        String mappedSeat = mapSeatIndexToLabel(s);
                                        if (!pOrder.getAvailableSeats().contains(mappedSeat)) {
                                            pOrder.getAvailableSeats().add(mappedSeat);
                                        }
                                    }
                                }
                                pBooking.setStatus("REJECTED");
                                rideBookingRepository.save(pBooking);
                            }
                        }
                    }
                }

                // Revert the passenger's original request order back to PENDING if all bookings are rejected
                if (pOrder.getStatus() != Order.OrderStatus.COMPLETED && pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                    boolean hasActiveBookings = false;
                    if (pOrder.getBookings() != null) {
                        for (com.waygo.backend.entity.RideBooking pb : pOrder.getBookings()) {
                            if (!"REJECTED".equals(pb.getStatus())) {
                                hasActiveBookings = true;
                                break;
                            }
                        }
                    }

                    if (!hasActiveBookings) {
                        pOrder.setDriver(null);
                        pOrder.setStatus(Order.OrderStatus.PENDING);
                        pOrder.setPassengerConfirmed(false);
                        pOrder.setLockedByDriverId(null);
                        pOrder.setLockExpirationTime(null);
                        if (pOrder.getAvailableSeats() != null) {
                            pOrder.getAvailableSeats().clear();
                        }

                        if (pOrder.getDriverOffers() != null) {
                            for (DriverOffer offer : pOrder.getDriverOffers()) {
                                if (offer.getDriver() != null && offer.getDriver().getId().equals(driver.getId())) {
                                    offer.setStatus("REJECTED");
                                } else {
                                    offer.setStatus("PENDING");
                                }
                            }
                        }

                        if (pOrder.getBookings() != null) {
                            for (com.waygo.backend.entity.RideBooking pBooking : pOrder.getBookings()) {
                                pBooking.setStatus("REJECTED");
                                rideBookingRepository.save(pBooking);
                            }
                        }
                        orderRepository.save(pOrder);
                        notificationService.notifyOrderStatusUpdate(pOrder);
                        notificationService.notifyNewOrder(pOrder);
                    } else {
                        orderRepository.save(pOrder);
                        notificationService.notifyOrderStatusUpdate(pOrder);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Order savedOrder = orderRepository.save(order);
        synchronizeAnnouncementToPassengerOrders(savedOrder);

        // Notify passenger about specific seat cancellation if seat parameter was provided
        if (seat != null && !seat.isEmpty()) {
            String seatName = mapSeatIndexToUzName(seat);
            notificationService.notifySeatCancelled(booking.getPassenger(), seatName, savedOrder);
        } else if ("REJECTED".equals(booking.getStatus())) {
            notificationService.notifyBookingRejected(booking);
        }

        notificationService.notifyOrderStatusUpdate(savedOrder);
        return savedOrder;
    }

    @Transactional
    public Order cancelBooking(Long bookingId, String seat) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null || (passenger.getRole() != User.Role.PASSENGER && passenger.getRole() != User.Role.DRIVER)) {
            throw new UnauthorizedAccessException("Only passengers can cancel bookings");
        }

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));

        if (!booking.getPassenger().getId().equals(passenger.getId())) {
            throw new UnauthorizedAccessException("You can only cancel your own bookings");
        }

        Order order = booking.getOrder();
        boolean wasAccepted = "ACCEPTED".equals(booking.getStatus());

        java.util.Set<Long> deletedBookingIds = new java.util.HashSet<>();
        boolean isDeleted = false;
        if (seat != null && !seat.isEmpty()) {
            if (booking.getSelectedSeats().contains(seat)) {
                booking.getSelectedSeats().remove(seat);

                if (order.getAvailableSeats() != null) {
                    String mappedSeat = mapSeatIndexToLabel(seat);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }

                if (booking.getSelectedSeats().isEmpty()) {
                    order.getBookings().remove(booking);
                    rideBookingRepository.delete(booking);
                    deletedBookingIds.add(booking.getId());
                    isDeleted = true;
                } else {
                    rideBookingRepository.save(booking);
                }
            }
        } else {
            // Free the seats (auto-occupy policy)
            if (order.getAvailableSeats() != null) {
                for (String s : booking.getSelectedSeats()) {
                    String mappedSeat = mapSeatIndexToLabel(s);
                    if (!order.getAvailableSeats().contains(mappedSeat)) {
                        order.getAvailableSeats().add(mappedSeat);
                    }
                }
            }
            order.getBookings().remove(booking);
            rideBookingRepository.delete(booking);
            deletedBookingIds.add(booking.getId());
            isDeleted = true;
        }

        // Sync with passenger request order
        try {
            Long pOrderId = booking.getPassengerOrderId();
            Order pOrder = null;
            if (pOrderId != null) {
                pOrder = orderRepository.findById(pOrderId).orElse(null);
            }

            if (pOrder != null) {
                boolean passengerBookingDeleted = false;

                // Find and update passenger's booking on pOrder
                if (pOrder.getBookings() != null) {
                    java.util.List<com.waygo.backend.entity.RideBooking> toRemove = new java.util.ArrayList<>();
                    for (com.waygo.backend.entity.RideBooking pBooking : pOrder.getBookings()) {
                        if (pBooking.getPassenger() != null && pBooking.getPassenger().getId().equals(passenger.getId())) {
                            if (seat != null && !seat.isEmpty()) {
                                if (pBooking.getSelectedSeats().contains(seat)) {
                                    pBooking.getSelectedSeats().remove(seat);
                                    if ("ACCEPTED".equals(pBooking.getStatus()) && pOrder.getAvailableSeats() != null) {
                                        String mappedSeat = mapSeatIndexToLabel(seat);
                                        if (!pOrder.getAvailableSeats().contains(mappedSeat)) {
                                            pOrder.getAvailableSeats().add(mappedSeat);
                                        }
                                    }
                                    if (pBooking.getSelectedSeats().isEmpty()) {
                                        toRemove.add(pBooking);
                                        rideBookingRepository.delete(pBooking);
                                        deletedBookingIds.add(pBooking.getId());
                                        passengerBookingDeleted = true;
                                    } else {
                                        rideBookingRepository.save(pBooking);
                                    }
                                }
                            } else {
                                if ("ACCEPTED".equals(pBooking.getStatus()) && pOrder.getAvailableSeats() != null) {
                                    for (String s : pBooking.getSelectedSeats()) {
                                        String mappedSeat = mapSeatIndexToLabel(s);
                                        if (!pOrder.getAvailableSeats().contains(mappedSeat)) {
                                            pOrder.getAvailableSeats().add(mappedSeat);
                                        }
                                    }
                                }
                                toRemove.add(pBooking);
                                rideBookingRepository.delete(pBooking);
                                deletedBookingIds.add(pBooking.getId());
                                passengerBookingDeleted = true;
                            }
                        }
                    }
                    pOrder.getBookings().removeAll(toRemove);
                }

                // If booking was fully deleted/reverted
                if (isDeleted || passengerBookingDeleted) {
                    if (pOrder.getStatus() != Order.OrderStatus.COMPLETED && pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                        // Check if there are any remaining non-rejected bookings on pOrder
                        boolean hasActiveBookings = false;
                        if (pOrder.getBookings() != null) {
                            for (com.waygo.backend.entity.RideBooking pb : pOrder.getBookings()) {
                                if (!"REJECTED".equals(pb.getStatus())) {
                                    hasActiveBookings = true;
                                    break;
                                }
                            }
                        }

                        if (!hasActiveBookings) {
                            pOrder.setDriver(null);
                            pOrder.setStatus(Order.OrderStatus.PENDING);
                            pOrder.setPassengerConfirmed(false);
                            pOrder.setLockedByDriverId(null);
                            pOrder.setLockExpirationTime(null);
                            if (pOrder.getAvailableSeats() != null) {
                                pOrder.getAvailableSeats().clear();
                            }
                            if (pOrder.getDriverOffers() != null) {
                                for (DriverOffer offer : pOrder.getDriverOffers()) {
                                    offer.setStatus("PENDING");
                                }
                            }
                            orderRepository.save(pOrder);
                            notificationService.notifyOrderStatusUpdate(pOrder);
                            notificationService.notifyNewOrder(pOrder);

                            // Find other bookings with same passengerOrderId and delete them
                            List<com.waygo.backend.entity.RideBooking> relatedBookings = rideBookingRepository.findByPassengerOrderId(pOrderId);
                            for (com.waygo.backend.entity.RideBooking rb : relatedBookings) {
                                if (!deletedBookingIds.contains(rb.getId())) {
                                    Order rbOrder = rb.getOrder();
                                    if (rbOrder != null) {
                                        if (rbOrder.getPassenger() == null) { // Driver announcement
                                            if (rbOrder.getAvailableSeats() != null) {
                                                for (String s : rb.getSelectedSeats()) {
                                                    String mappedSeat = mapSeatIndexToLabel(s);
                                                    if (!rbOrder.getAvailableSeats().contains(mappedSeat)) {
                                                        rbOrder.getAvailableSeats().add(mappedSeat);
                                                    }
                                                }
                                            }
                                            rbOrder.getBookings().remove(rb);
                                            rideBookingRepository.delete(rb);
                                            deletedBookingIds.add(rb.getId());
                                            orderRepository.save(rbOrder);
                                            notificationService.notifyOrderStatusUpdate(rbOrder);
                                        } else {
                                            // Passenger request order booking
                                            rbOrder.getBookings().remove(rb);
                                            rideBookingRepository.delete(rb);
                                            deletedBookingIds.add(rb.getId());
                                            orderRepository.save(rbOrder);
                                            notificationService.notifyOrderStatusUpdate(rbOrder);
                                        }
                                    }
                                }
                            }
                        } else {
                            orderRepository.save(pOrder);
                            notificationService.notifyOrderStatusUpdate(pOrder);
                        }
                    }
                } else {
                    orderRepository.save(pOrder);
                    notificationService.notifyOrderStatusUpdate(pOrder);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        notificationService.notifyDriverOrderCancelledByPassenger(savedOrder);
        return savedOrder;
    }
    private void synchronizeAnnouncementToPassengerOrders(Order announcement) {
        if (announcement == null || announcement.getDriver() == null || announcement.getPassenger() != null) {
            return;
        }
        if (announcement.getBookings() == null) {
            return;
        }
        for (com.waygo.backend.entity.RideBooking booking : announcement.getBookings()) {
            if (booking.getPassengerOrderId() != null) {
                try {
                    orderRepository.findById(booking.getPassengerOrderId()).ifPresent(pOrder -> {
                        // Keep availableSeats in sync
                        if (pOrder.getAvailableSeats() == null) {
                            pOrder.setAvailableSeats(new java.util.ArrayList<>());
                        }
                        pOrder.getAvailableSeats().clear();
                        if (announcement.getAvailableSeats() != null) {
                            pOrder.getAvailableSeats().addAll(announcement.getAvailableSeats());
                        }

                        // Keep bookings in sync
                        if (pOrder.getBookings() != null) {
                            for (com.waygo.backend.entity.RideBooking pb : pOrder.getBookings()) {
                                if (pb.getPassenger() != null && pb.getPassenger().getId().equals(booking.getPassenger().getId())) {
                                    pb.setStatus(booking.getStatus());
                                    if (booking.getSelectedSeats() != null) {
                                        pb.setSelectedSeats(new java.util.ArrayList<>(booking.getSelectedSeats()));
                                    } else {
                                        pb.setSelectedSeats(new java.util.ArrayList<>());
                                    }
                                    rideBookingRepository.save(pb);
                                }
                            }
                        }

                        orderRepository.save(pOrder);
                        notificationService.notifyOrderStatusUpdate(pOrder);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
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

    private String mapSeatIndexToUzName(String index) {
        if (index == null) return "";
        switch (index) {
            case "1": return "Old o'ng";
            case "2": return "Orqa chap";
            case "3": return "Orqa o'rta";
            case "4": return "Orqa o'ng";
            default: return "O'rindiq " + index;
        }
    }

    private void checkDriverBilling(User driver) {
        if (driver != null && driver.getRole() == User.Role.DRIVER && driver.isBillingEnabled()) {
            throw new IllegalStateException("To'lov tizimi faolligi sababli amallar taqiqlangan. Iltimos, to'lovni amalga oshiring.");
        }
    }

    private String resolvePickupAddress(Order order, String fallbackPickup) {
        if (order != null && order.getFromLat() != null && order.getFromLon() != null) {
            String baseAddr = (fallbackPickup == null || fallbackPickup.trim().isEmpty()) 
                ? (order.getFromAddress() != null ? order.getFromAddress() : "") 
                : fallbackPickup;
            // Prevent duplicate LAT/LON appending
            if (baseAddr.contains("[LAT:")) {
                return baseAddr;
            }
            return String.format("%s [LAT:%s, LON:%s]", baseAddr, order.getFromLat(), order.getFromLon());
        }
        return fallbackPickup;
    }
}
