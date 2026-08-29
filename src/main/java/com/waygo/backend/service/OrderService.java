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
    private final SecurityUtils securityUtils;
    private final NotificationService notificationService;
    private final DriverProfileRepository driverProfileRepository;
    private final com.waygo.backend.repository.RideBookingRepository rideBookingRepository;
    private final com.waygo.backend.repository.UserRepository userRepository;
    private final DriverLocationCache driverLocationCache;

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
        // Locking is an internal soft-reservation (order.getStatus() never
        // changes) — WebSocket-refresh other viewers, but don't push a
        // misleading "status updated: PENDING" notification for it.
        notificationService.notifyOrderStatusUpdate(savedOrder, true, false);
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
            // Same reasoning as lockOrder above — status never changes here either.
            notificationService.notifyOrderStatusUpdate(savedOrder, true, false);
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

        if (order.getLockedByDriverId() != null && !order.getLockedByDriverId().equals(driver.getId())
                && order.getLockExpirationTime() != null && order.getLockExpirationTime().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("Buyurtma ayni paytda boshqa haydovchi tomonidan ko'rib chiqilmoqda");
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
                    isRouteMatching(order.getFromAddress(), otherOrder.getFromAddress()) &&
                    isRouteMatching(order.getToAddress(), otherOrder.getToAddress())) {

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
        // A new DriverOffer, not a status change (order stays PENDING) — see
        // notifyNewDriverOffer's doc comment for why this can't reuse
        // notifyOrderStatusUpdate's generic (and here, misleading) push text.
        // Passes THIS offer's own price, not savedOrder.getPrice() — with
        // several drivers able to bid different prices on one request, the
        // order's own price is meaningless here (and, separately, is
        // presently always a hardcoded placeholder from the passenger app —
        // see order_form_bloc.dart — so it would show every passenger the
        // exact same wrong number regardless of what any driver offered).
        notificationService.notifyNewDriverOffer(savedOrder, offer.getPricePerPerson());
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

        // The passenger declared how many people are traveling when they created
        // this request (shown to the driver as "Number of passengers: N, mark
        // that many seats" — see waygo_driver's passengerCountMarkSeatsMessage).
        // Nothing enforced that the driver's offer / the confirming client
        // actually reserved that many seats, so a mismatch (e.g. passenger said
        // 3 but only 1 seat gets booked) silently went through. Only enforced
        // when passengerCount is set, to stay compatible with older orders that
        // predate this field.
        if (order.getPassengerCount() != null && seatsToBook.size() != order.getPassengerCount()) {
            throw new IllegalArgumentException(
                "Yo'lovchilar soni (" + order.getPassengerCount() + ") bilan tanlangan o'rindiqlar soni ("
                        + seatsToBook.size() + ") mos kelmayapti");
        }

        // The pickup string may carry a custom point the passenger chose
        // (e.g. "Some street [LAT:41.3,LON:69.2]") distinct from the order's
        // own fromLat/fromLon. resolvePickupAddress() below already keeps
        // that text (and its embedded coordinate) intact for pickupAddress,
        // but fromLat/fromLon were always being set to the order's original
        // point regardless — silently discarding the custom coordinate, so
        // every driver-side screen that reads fromLat/fromLon directly
        // (rather than re-parsing pickupAddress) showed the wrong pin. Parse
        // it out here too, same as bookRide() already does.
        Double customPickupLat = null;
        Double customPickupLon = null;
        if (pickup.contains("[LAT:") && pickup.contains("LON:")) {
            try {
                int latIdx = pickup.indexOf("[LAT:");
                int commaIdx = pickup.indexOf(",", latIdx);
                int lonIdx = pickup.indexOf("LON:", commaIdx);
                int endBracket = pickup.indexOf("]", lonIdx);
                if (latIdx != -1 && commaIdx != -1 && lonIdx != -1 && endBracket != -1) {
                    customPickupLat = Double.parseDouble(pickup.substring(latIdx + 5, commaIdx).trim());
                    customPickupLon = Double.parseDouble(pickup.substring(lonIdx + 4, endBracket).trim());
                }
            } catch (Exception e) {}
        }

        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(seatsToBook)
                .status("ACCEPTED")
                .passengerOrderId(order.getId())
                .pickupAddress(resolvePickupAddress(order, pickup))
                .fromLat(customPickupLat != null ? customPickupLat
                        : order.getPickupLat() != null ? order.getPickupLat() : order.getFromLat())
                .fromLon(customPickupLon != null ? customPickupLon
                        : order.getPickupLon() != null ? order.getPickupLon() : order.getFromLon())
                .toLat(order.getToLat())
                .toLon(order.getToLon())
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
        User driver = chosenOffer.getDriver();
        Order activeAnnouncement = findActiveAnnouncementForRoute(
            driver.getId(),
            order.getDepartureDate(),
            order.getFromAddress(),
            order.getToAddress()
        );

        if (activeAnnouncement != null) {
            // Someone may have booked these exact seats directly on the driver's
            // announcement (or via another offer) between the time this offer's
            // seat count was calculated and this confirmation, so re-check
            // availability against the announcement's current state before merging.
            java.util.List<String> currentSeats = activeAnnouncement.getAvailableSeats();
            for (String seatNum : seatsToBook) {
                String seatLabel = seatNum.equals("1") ? "FRONT"
                        : seatNum.equals("2") ? "BACK_LEFT"
                        : seatNum.equals("3") ? "BACK_CENTER"
                        : seatNum.equals("4") ? "BACK_RIGHT"
                        : "";
                if (!seatLabel.isEmpty() && (currentSeats == null || !currentSeats.contains(seatLabel))) {
                    throw new IllegalStateException("Tanlangan o'rindiq boshqa yo'lovchi tomonidan band qilingan. Iltimos, boshqa o'rindiq tanlang.");
                }
            }
        }

        boolean announcementDelivered = false;
        try {
            if (activeAnnouncement == null) {
                Order.OrderBuilder builder = Order.builder()
                        .driver(driver)
                        .passenger(null)
                        .fromAddress(order.getFromAddress())
                        .toAddress(order.getToAddress())
                        .fromLat(order.getFromLat())
                        .fromLon(order.getFromLon())
                        .toLat(order.getToLat())
                        .toLon(order.getToLon())
                        .pickupAddress(resolvePickupAddress(order, pickup))
                        .pickupLat(customPickupLat != null ? customPickupLat
                                : order.getPickupLat() != null ? order.getPickupLat() : order.getFromLat())
                        .pickupLon(customPickupLon != null ? customPickupLon
                                : order.getPickupLon() != null ? order.getPickupLon() : order.getFromLon())
                        .departureDate(order.getDepartureDate())
                        .departureTime(order.getDepartureTime())
                        .price(chosenOffer.getPricePerPerson())
                        .passengerCount(4)
                        .status(Order.OrderStatus.PENDING)
                        .createdAt(LocalDateTime.now());

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
                    .fromLat(customPickupLat != null ? customPickupLat
                            : order.getPickupLat() != null ? order.getPickupLat() : order.getFromLat())
                    .fromLon(customPickupLon != null ? customPickupLon
                            : order.getPickupLon() != null ? order.getPickupLon() : order.getFromLon())
                    .toLat(order.getToLat())
                    .toLon(order.getToLon())
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
            announcementDelivered = true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        // The driver already received the trip as `activeAnnouncement` above (that's
        // the Order row their UI is meant to render as the active trip). Pushing
        // `savedOrder` to the driver too would show the same trip as a second card
        // (see NotificationService#notifyOrderStatusUpdate javadoc). Only fall back
        // to notifying the driver via `savedOrder` if the announcement push failed.
        notificationService.notifyOrderStatusUpdate(savedOrder, !announcementDelivered);
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
        // Same reasoning as lockOrder/unlockOrder — order.getStatus() is
        // never touched here, only a RideBooking is created and seats are
        // removed from availableSeats, so the generic "status updated" push
        // text would be misleading.
        notificationService.notifyOrderStatusUpdate(savedOrder, true, false);
        return savedOrder;
    }

    @Transactional
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

        if (order.getDriver() == null) {
            throw new IllegalStateException("Order has no assigned driver");
        }

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

        // Fares are settled directly between driver and passenger outside the
        // app (cash) — this app's wallet balance is only ever used by
        // drivers to buy a tariff plan (see checkDriverBilling above). A
        // previous version of this method also ran an in-app fare transfer
        // here via transactionService.processPayment(passenger -> driver),
        // which required the PASSENGER to have a sufficient app balance;
        // since passengers never top up or pay through the app in this
        // product, that check failed on effectively every completion and
        // blocked trip completion outright (surfaced client-side as a
        // misleading "haydovchining hisobida yetarli mablag' yo'q" error,
        // which was also mislabeled — it was actually the passenger's
        // balance being checked). Removed; trip completion no longer moves
        // any money through the app.
        if (order.getBookings() != null) {
            // Mark route-booking passengers' completion for all
            // accepted/collected bookings on this driver announcement. By
            // the time the trip is completed, an on-route passenger's
            // booking has usually progressed to COLLECTED (picked up during
            // the ride); ACCEPTED is kept too in case a booking never went
            // through an explicit collection step.
            for (com.waygo.backend.entity.RideBooking booking : order.getBookings()) {
                if (booking.getStatus() != null &&
                    ("ACCEPTED".equalsIgnoreCase(booking.getStatus()) || "COLLECTED".equalsIgnoreCase(booking.getStatus()))) {
                    // Try to find the corresponding passenger request order and mark it as COMPLETED too
                    try {
                        if (booking.getPassengerOrderId() != null) {
                            orderRepository.findById(booking.getPassengerOrderId()).ifPresent(pOrder -> {
                                if (pOrder.getStatus() != Order.OrderStatus.COMPLETED && pOrder.getStatus() != Order.OrderStatus.CANCELLED) {
                                    pOrder.setStatus(Order.OrderStatus.COMPLETED);
                                    orderRepository.save(pOrder);
                                    // sendFcmPush=false: notifyTripCompleted(savedOrder) below already
                                    // sends this same passenger the TRIP_COMPLETED push via the route's
                                    // bookings loop — sending it here too doubled the "rate your trip"
                                    // notification.
                                    notificationService.notifyOrderStatusUpdate(pOrder, true, false);
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
                                    // sendFcmPush=false: see comment above — avoids doubling the
                                    // TRIP_COMPLETED push that notifyTripCompleted(savedOrder) sends.
                                    notificationService.notifyOrderStatusUpdate(pOrder, true, false);
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
        }

        order.setStatus(Order.OrderStatus.COMPLETED);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);
        notificationService.notifyTripCompleted(savedOrder);
        return savedOrder;
    }

    /**
     * Updates the passenger's precise pickup point ("Olib ketish joyi") on an
     * already-created order and pings the assigned driver over WebSocket so
     * their live map redraws the route to the new point immediately.
     * Deliberately independent of {@link #updateOrder}: that method blocks
     * any edit once the order is ACCEPTED/ARRIVED/STARTED because it also
     * covers destination/price/seat changes a driver has already committed
     * to, but adjusting just the meeting pin is expected to keep working
     * right up until the trip actually starts.
     */
    @Transactional
    public Order updatePickupLocation(Long orderId, Double lat, Double lon, String address) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null || order.getPassenger() == null
                || !currentUser.getId().equals(order.getPassenger().getId())) {
            throw new UnauthorizedAccessException("You can only update the pickup location of your own order");
        }

        boolean editable = order.getStatus() == Order.OrderStatus.PENDING
                || order.getStatus() == Order.OrderStatus.ACCEPTED
                || order.getStatus() == Order.OrderStatus.ARRIVED;
        if (!editable) {
            throw new IllegalStateException("Pickup location can no longer be changed for this trip");
        }

        order.setPickupLat(lat);
        order.setPickupLon(lon);
        if (address != null) {
            order.setPickupAddress(address);
        }
        Order saved = orderRepository.save(order);

        // Driver-facing pickup point may already be mirrored into a RideBooking
        // (created at offer-confirmation/join time via resolvePickupAddress) —
        // that snapshot doesn't auto-follow later edits, so sync it here too.
        List<com.waygo.backend.entity.RideBooking> linkedPickupBookings =
                rideBookingRepository.findByPassengerOrderId(order.getId());
        for (com.waygo.backend.entity.RideBooking booking : linkedPickupBookings) {
            booking.setFromLat(lat);
            booking.setFromLon(lon);
            if (address != null) {
                booking.setPickupAddress(address);
            }
        }
        if (!linkedPickupBookings.isEmpty()) {
            rideBookingRepository.saveAll(linkedPickupBookings);
        }

        if (saved.getDriver() != null) {
            // WS ping to move the driver's live map, plus a dedicated push
            // (not the generic status-update one, which would misleadingly
            // say "Buyurtma holati yangilandi" for something that isn't a
            // status change) so the driver actually notices the pin moved.
            notificationService.notifyOrderStatusUpdate(saved, true, false);
            notificationService.notifyPickupLocationChanged(saved);
        } else {
            // Still PENDING — no single assigned driver exists yet to target
            // a personal push at. Any driver currently previewing this
            // order's details before making an offer needs the same live
            // update, so re-broadcast on the same public channel new pending
            // orders go out on; their already-open detail page's WS listener
            // picks it up by matching order id, same as an assigned driver's
            // does above.
            notificationService.notifyPendingOrderUpdated(saved);
        }

        return saved;
    }

    @Transactional
    public Order rateDriver(Long orderId, Double rating, String comment, List<String> tags) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedAccessException("You can only rate the driver of your own order");
        }

        // Two distinct ways a user can legitimately be "the passenger" on
        // this order: directly via order.getPassenger() (a solo,
        // passenger-created order), or via a RideBooking (a shared
        // driver-announcement order — order.getPassenger() is null on
        // those, see getPassengerHistory/completeTrip; each seat-booker is
        // tracked as their own RideBooking instead of their own Order).
        // Rating must be recorded against whichever of those two actually
        // identifies this caller, since several different passengers can
        // share one driver-announcement order and each needs to rate (and
        // be blocked from re-rating) independently.
        boolean isDirectPassenger = order.getPassenger() != null
                && currentUser.getId().equals(order.getPassenger().getId());
        com.waygo.backend.entity.RideBooking myBooking = null;
        if (!isDirectPassenger && order.getBookings() != null) {
            for (com.waygo.backend.entity.RideBooking b : order.getBookings()) {
                if (b.getPassenger() != null && currentUser.getId().equals(b.getPassenger().getId())) {
                    myBooking = b;
                    break;
                }
            }
        }
        if (!isDirectPassenger && myBooking == null) {
            throw new UnauthorizedAccessException("You can only rate the driver of your own order");
        }

        if (order.getStatus() != Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("You can only rate the driver after the trip is completed");
        }

        boolean alreadyRated = isDirectPassenger ? order.getRating() != null : myBooking.getRating() != null;
        if (alreadyRated) {
            throw new IllegalStateException("This order has already been rated");
        }

        User driver = order.getDriver();
        if (driver == null) {
            throw new IllegalStateException("No driver is assigned to this order");
        }

        // Denominator is ratingCount (how many ratings have actually landed
        // on `driver.rating`), not tripsCount (how many trips this driver
        // has completed). They diverge on a multi-passenger route trip: one
        // completed trip, but each route passenger rates separately here —
        // each such call is a genuinely new data point that must count
        // once, regardless of how tripsCount moved. The alreadyRated guard
        // above additionally stops the same passenger from being counted
        // twice if this endpoint is called again for their order/booking.
        double currentRating = driver.getRating() != null ? driver.getRating() : 5.0;
        int currentRatingCount = driver.getRatingCount() != null ? driver.getRatingCount() : 0;
        int newRatingCount = currentRatingCount + 1;

        double updatedRating = ((currentRating * currentRatingCount) + rating) / newRatingCount;
        updatedRating = Math.max(1.0, Math.min(5.0, updatedRating));

        driver.setRating(updatedRating);
        driver.setRatingCount(newRatingCount);
        userRepository.save(driver);

        // Deferred to after this @Transactional method actually commits — sent
        // eagerly here (mid-transaction), a driver app that reacts to the
        // RATING_UPDATE push by immediately calling GET /auth/me could have
        // that request served (by a different DB connection) before this
        // transaction's UPDATE is visible, reading back the pre-rating value
        // and making the new rating look like it never took effect.
        User driverToNotify = driver;
        double ratingToNotify = updatedRating;
        int ratingCountToNotify = newRatingCount;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        notificationService.notifyRatingUpdate(driverToNotify, ratingToNotify, ratingCountToNotify);
                    }
                }
            );
        } else {
            notificationService.notifyRatingUpdate(driverToNotify, ratingToNotify, ratingCountToNotify);
        }

        if (isDirectPassenger) {
            order.setRating(rating);
            order.setComment(comment);
            if (tags != null) {
                order.setFeedbackTags(tags);
            }
            orderRepository.save(order);
        } else {
            myBooking.setRating(rating);
            myBooking.setComment(comment);
            if (tags != null) {
                myBooking.setFeedbackTags(tags);
            }
            rideBookingRepository.save(myBooking);

            // Every read path (getPassengerHistory, getOrderById,
            // isOrderAlreadyRated on the client) only ever inspects
            // Order.rating, never RideBooking.rating directly — without
            // this, the submit above succeeds but the trip permanently
            // looks unrated everywhere else the app shows it. Mirrors the
            // rating/comment onto this passenger's own personal Order when
            // one exists (see confirmDriverOffer/syncPassengerOrdersToStatus
            // for the same passengerOrderId linkage pattern). Booking-only
            // passengers with no personal Order still rely on
            // overlayCurrentUserBookingRating() at read time below.
            if (myBooking.getPassengerOrderId() != null) {
                orderRepository.findById(myBooking.getPassengerOrderId()).ifPresent(pOrder -> {
                    pOrder.setRating(rating);
                    pOrder.setComment(comment);
                    if (tags != null) {
                        pOrder.setFeedbackTags(tags);
                    }
                    orderRepository.save(pOrder);
                });
            }
        }

        notificationService.notifyOrderStatusUpdate(order);
        return order;
    }

    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        Order.OrderStatus previousStatus = order.getStatus();

        if (status == Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("Use the complete-trip endpoint to complete an order");
        }

        User currentUser = securityUtils.getCurrentUser();
        if (currentUser != null && currentUser.getRole() == User.Role.DRIVER) {
            checkDriverBilling(currentUser);
        }
        boolean isPassenger = currentUser != null && order.getPassenger() != null && currentUser.getId().equals(order.getPassenger().getId());
        boolean isDriver = currentUser != null && order.getDriver() != null && currentUser.getId().equals(order.getDriver().getId());

        if (!isPassenger && !isDriver) {
            throw new UnauthorizedAccessException("You are not part of this order");
        }

        // Driver un-starting a trip they started by mistake: only the assigned
        // driver may revert STARTED back to ACCEPTED.
        if (previousStatus == Order.OrderStatus.STARTED && status == Order.OrderStatus.ACCEPTED && !isDriver) {
            throw new UnauthorizedAccessException("Faqat haydovchi safarni boshlanishini bekor qila oladi");
        }

        if ((status == Order.OrderStatus.ACCEPTED || status == Order.OrderStatus.ARRIVED || status == Order.OrderStatus.STARTED) && !isDriver) {
            throw new UnauthorizedAccessException("Only the assigned driver can set this status");
        }

        if (status == Order.OrderStatus.CANCELLED && isPassenger) {
            if (order.getStatus() == Order.OrderStatus.STARTED || order.getStatus() == Order.OrderStatus.ARRIVED || order.getStatus() == Order.OrderStatus.COMPLETED) {
                throw new IllegalStateException("Safar boshlanganligi sababli buyurtmani bekor qila olmaysiz. Iltimos, haydovchi bilan bog'laning.");
            }
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

            // 1b. The order itself is being cancelled outright, so any driver
            // requests still waiting for a passenger response (no contract
            // formed yet) no longer have anything to respond to — reject
            // them so they stop showing as an active/pending request on the
            // driver's side.
            if (order.getDriverOffers() != null) {
                for (DriverOffer offer : order.getDriverOffers()) {
                    if ("PENDING".equals(offer.getStatus())) {
                        offer.setStatus("REJECTED");
                    }
                }
            }

            // 2. Notify the driver if they were already assigned, and — since a
            // contract had already been formed — release the order back to the
            // public pool instead of leaving it CANCELLED-but-still-assigned
            // (which getPendingOrders()'s status=PENDING/driver=null query would
            // never surface again, hiding it from every other driver forever).
            // Also clear the winning offer's ACCEPTED status so it stops showing
            // as the confirmed driver in the passenger's own offers list.
            if (order.getDriver() != null) {
                notificationService.notifyDriverOrderCancelledByPassenger(order);

                if (order.getDriverOffers() != null) {
                    for (DriverOffer offer : order.getDriverOffers()) {
                        if (offer.getDriver() != null
                                && offer.getDriver().getId().equals(order.getDriver().getId())
                                && "ACCEPTED".equals(offer.getStatus())) {
                            offer.setStatus("CANCELLED");
                        }
                    }
                }

                order.setDriver(null);
                order.setPassengerConfirmed(false);
                order.setLockedByDriverId(null);
                order.setLockExpirationTime(null);
                order.setStatus(Order.OrderStatus.PENDING);

                Order savedOrder = orderRepository.save(order);
                notificationService.notifyOrderStatusUpdate(savedOrder);
                notificationService.notifyNewOrder(savedOrder);
                return savedOrder;
            }
        }

        if (status == Order.OrderStatus.CANCELLED && isDriver) {
            if (order.getPassenger() != null) {
                // This order may have been assigned via confirmDriverOffer(), which
                // mirrors the passenger's seats into a *separate* driver-owned
                // announcement Order (see the "Auto-create or Update driver's ride
                // announcement" block above) linked back by RideBooking.passengerOrderId.
                // The old code below (release to PENDING) ignored that mirror
                // entirely: the driver's own announcement was left behind, still
                // PENDING with its booking stuck ACCEPTED forever — orphaned and
                // never cancellable from the driver's side, which is exactly what
                // was reported. Passenger-initiated cancel already cleans this up
                // correctly (see the isPassenger branch above); mirror that here.
                List<com.waygo.backend.entity.RideBooking> mirroredBookings =
                        rideBookingRepository.findByPassengerOrderId(order.getId());
                if (!mirroredBookings.isEmpty()) {
                    for (com.waygo.backend.entity.RideBooking booking : mirroredBookings) {
                        Order driverOrder = booking.getOrder();
                        if (driverOrder != null) {
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

                    // Released back to PENDING (not left CANCELLED), same as the
                    // no-mirrored-booking branch just below — a contract the
                    // driver walks away from must become visible to other
                    // drivers again, not vanish from the pool.
                    if (order.getDriverOffers() != null) {
                        for (DriverOffer offer : order.getDriverOffers()) {
                            if (offer.getDriver() != null
                                    && offer.getDriver().getId().equals(currentUser.getId())
                                    && "ACCEPTED".equals(offer.getStatus())) {
                                offer.setStatus("CANCELLED");
                            }
                        }
                    }

                    order.setStatus(Order.OrderStatus.PENDING);
                    order.setDriver(null);
                    order.setPassengerConfirmed(false);
                    order.setLockedByDriverId(null);
                    order.setLockExpirationTime(null);

                    Order savedOrder = orderRepository.save(order);
                    notificationService.notifyPassengerOrderCancelledByDriver(savedOrder, savedOrder);
                    notificationService.notifyOrderStatusUpdate(savedOrder);
                    notificationService.notifyNewOrder(savedOrder);
                    return savedOrder;
                }

                // No mirrored booking — this is a plain direct-assign order that
                // was still confirmed via a DriverOffer (the contract). Clear that
                // offer too, otherwise it stays ACCEPTED and shows as a live
                // request/contract to the passenger even after the order is
                // released back to PENDING.
                if (order.getDriverOffers() != null) {
                    for (DriverOffer offer : order.getDriverOffers()) {
                        if (offer.getDriver() != null
                                && offer.getDriver().getId().equals(currentUser.getId())
                                && "ACCEPTED".equals(offer.getStatus())) {
                            offer.setStatus("CANCELLED");
                        }
                    }
                }

                // Release it back to PENDING so another driver can pick it up,
                // same as before.
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
                        boolean isConfirmed = "ACCEPTED".equals(booking.getStatus()) || "COLLECTED".equals(booking.getStatus());
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
                                        if (pOrder.getDriverOffers() != null) {
                                            for (DriverOffer offer : pOrder.getDriverOffers()) {
                                                if (offer.getDriver() != null
                                                        && offer.getDriver().getId().equals(order.getDriver().getId())
                                                        && "ACCEPTED".equals(offer.getStatus())) {
                                                    offer.setStatus("CANCELLED");
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
                                        if (pOrder.getDriverOffers() != null) {
                                            for (DriverOffer offer : pOrder.getDriverOffers()) {
                                                if (offer.getDriver() != null
                                                        && offer.getDriver().getId().equals(order.getDriver().getId())
                                                        && "ACCEPTED".equals(offer.getStatus())) {
                                                    offer.setStatus("CANCELLED");
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
        // (COMPLETED is handled by completeTrip, which updateStatus no longer accepts)
        if (order.getPassenger() == null && (status == Order.OrderStatus.ARRIVED || status == Order.OrderStatus.STARTED)) {
            syncPassengerOrdersToStatus(order, status, java.util.Set.of("ACCEPTED"));
        }

        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderStatusUpdate(savedOrder);

        // When order status becomes STARTED or ARRIVED, notify the first uncollected passenger in sequence
        if (status == Order.OrderStatus.STARTED || status == Order.OrderStatus.ARRIVED) {
            List<com.waygo.backend.entity.RideBooking> bookings = rideBookingRepository.findByOrderId(savedOrder.getId());
            if (bookings != null) {
                for (com.waygo.backend.entity.RideBooking b : bookings) {
                    if (b != null && !"COLLECTED".equalsIgnoreCase(b.getStatus()) && !"REJECTED".equalsIgnoreCase(b.getStatus()) && !"CANCELLED".equalsIgnoreCase(b.getStatus()) && b.getPassenger() != null) {
                        sendNextPassengerTurnNotification(b, savedOrder.getDriver(), savedOrder.getId());
                        break; // Notify the 1st passenger in sequence!
                    }
                }
            }
        }

        // When the driver actually starts the trip (after every passenger on the
        // route has already been collected), push a distinct "trip started"
        // notification to every passenger on the route. By this point all their
        // bookings are COLLECTED, so the "next uncollected passenger" loop above
        // finds nobody and nobody would otherwise get an FCM push for this event.
        if (status == Order.OrderStatus.STARTED) {
            notificationService.notifyTripStarted(savedOrder);
        }

        // Driver reverted a trip they had just started back to ACCEPTED — let
        // every passenger on the route know the trip is not actually underway
        // yet, since some of them may already have received the "trip started"
        // push above and could start acting on it (e.g. heading to a meeting
        // point) before the driver changed their mind.
        if (previousStatus == Order.OrderStatus.STARTED && status == Order.OrderStatus.ACCEPTED) {
            notificationService.notifyTripStartCancelled(savedOrder);
        }

        if (status == Order.OrderStatus.COMPLETED) {
            notificationService.notifyTripCompleted(savedOrder);
        }

        return savedOrder;
    }

    /**
     * Propagates a driver announcement's status to each booked passenger's own
     * request order, for bookings currently in one of {@code eligibleBookingStatuses}.
     * Leaves any passenger order already COMPLETED or CANCELLED untouched.
     */
    private void syncPassengerOrdersToStatus(Order order, Order.OrderStatus status, java.util.Set<String> eligibleBookingStatuses) {
        if (order.getBookings() == null) {
            return;
        }
        for (com.waygo.backend.entity.RideBooking booking : order.getBookings()) {
            if (booking.getStatus() == null || !eligibleBookingStatuses.contains(booking.getStatus().toUpperCase())) {
                continue;
            }
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

    private void sendNextPassengerTurnNotification(com.waygo.backend.entity.RideBooking b, User driver, Long orderId) {
        if (b == null || b.getPassenger() == null) return;
        Double lat = b.getFromLat();
        Double lon = b.getFromLon();
        if ((lat == null || lon == null) && b.getPassengerOrderId() != null) {
            java.util.Optional<Order> pOpt = orderRepository.findById(b.getPassengerOrderId());
            if (pOpt.isPresent()) {
                lat = pOpt.get().getFromLat();
                lon = pOpt.get().getFromLon();
            }
        }
        notificationService.notifyNextPassengerTurn(b.getPassenger(), driver, orderId, lat, lon);
    }

    @Transactional
    public Order getOrderById(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User currentUser = securityUtils.getCurrentUser();
        boolean isPassenger = currentUser != null && order.getPassenger() != null && currentUser.getId().equals(order.getPassenger().getId());
        boolean isDriver = currentUser != null && order.getDriver() != null && currentUser.getId().equals(order.getDriver().getId());
        boolean isAdmin = currentUser != null && currentUser.getRole() == User.Role.ADMIN;
        // A route/announcement order has order.getPassenger() == null — each
        // seat-booker is only ever attached via their own RideBooking, so the
        // plain isPassenger check above never matches them and they'd
        // otherwise get a 403 fetching an order they're legitimately on.
        boolean isBookingPassenger = currentUser != null && !isPassenger && order.getBookings() != null
                && order.getBookings().stream().anyMatch(b -> b != null && b.getPassenger() != null
                        && currentUser.getId().equals(b.getPassenger().getId()));
        if (!isPassenger && !isDriver && !isAdmin && !isBookingPassenger) {
            throw new UnauthorizedAccessException("You are not authorized to view this order");
        }

        if (order.getBookings() != null) {
            for (com.waygo.backend.entity.RideBooking b : order.getBookings()) {
                if (b != null && b.getPassengerOrderId() != null) {
                    orderRepository.findById(b.getPassengerOrderId()).ifPresent(pOrder -> {
                        if (b.getFromLat() == null) b.setFromLat(pOrder.getFromLat());
                        if (b.getFromLon() == null) b.setFromLon(pOrder.getFromLon());
                        if (b.getToLat() == null) b.setToLat(pOrder.getToLat());
                        if (b.getToLon() == null) b.setToLon(pOrder.getToLon());
                    });
                }
            }
        }
        overlayCurrentUserBookingRating(order, currentUser);
        return order;
    }

    /**
     * For a driver-announcement order (order.getPassenger() == null) viewed
     * by one of its RideBooking passengers, overlays that passenger's own
     * booking-level rating/comment onto the in-memory Order object before
     * it's returned to them. order.getRating() is otherwise always null for
     * announcement orders — rateDriver() persists a booking-only passenger's
     * rating on RideBooking (see its doc comment), not Order, so without
     * this a successful rating submit looked like it silently failed
     * everywhere the app reads order.rating (history list, order detail,
     * the client's "already rated" check). Purely an in-memory view overlay
     * on the object being returned for THIS request — never persisted.
     */
    private void overlayCurrentUserBookingRating(Order order, User currentUser) {
        if (order == null || order.getPassenger() != null || currentUser == null || order.getBookings() == null) {
            return;
        }
        for (com.waygo.backend.entity.RideBooking b : order.getBookings()) {
            if (b.getPassenger() != null && currentUser.getId().equals(b.getPassenger().getId())) {
                if (b.getRating() != null) {
                    order.setRating(b.getRating());
                    order.setComment(b.getComment());
                }
                break;
            }
        }
    }

    public List<Order> getPassengerHistory(Long passengerId, int page, int size) {
        List<Order> all = getPassengerHistory(passengerId);
        int start = Math.min(page * size, all.size());
        int end = Math.min(start + size, all.size());
        return all.subList(start, end);
    }

    public List<Order> getPassengerHistory(Long passengerId) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null || (!currentUser.getId().equals(passengerId) && currentUser.getRole() != User.Role.ADMIN)) {
            throw new UnauthorizedAccessException("You are not authorized to view this passenger's history");
        }

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
                overlayCurrentUserBookingRating(o, currentUser);
            }
            filtered.add(o);
        }

        return filtered;
    }

    @Transactional
    public List<com.waygo.backend.entity.RideBooking> getMyBookings() {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedAccessException("Not authenticated");
        }
        List<com.waygo.backend.entity.RideBooking> bookings = rideBookingRepository.findByPassengerId(currentUser.getId());
        if (bookings != null) {
            for (com.waygo.backend.entity.RideBooking b : bookings) {
                if (b != null && b.getPassengerOrderId() != null) {
                    orderRepository.findById(b.getPassengerOrderId()).ifPresent(pOrder -> {
                        if (b.getFromLat() == null) b.setFromLat(pOrder.getFromLat());
                        if (b.getFromLon() == null) b.setFromLon(pOrder.getFromLon());
                        if (b.getToLat() == null) b.setToLat(pOrder.getToLat());
                        if (b.getToLon() == null) b.setToLon(pOrder.getToLon());
                        if (b.getPickupAddress() == null) b.setPickupAddress(pOrder.getFromAddress());
                    });
                }
            }
        }
        return bookings;
    }

    public List<Order> getDriverHistory(Long driverId, int page, int size) {
        List<Order> all = getDriverHistory(driverId);
        int start = Math.min(page * size, all.size());
        int end = Math.min(start + size, all.size());
        return all.subList(start, end);
    }

    public List<Order> getDriverHistory(Long driverId) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null || (!currentUser.getId().equals(driverId) && currentUser.getRole() != User.Role.ADMIN)) {
            throw new UnauthorizedAccessException("You are not authorized to view this driver's history");
        }

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

        // Check if the order status allows editing (only while still pending)
        if (order.getStatus() == Order.OrderStatus.COMPLETED ||
            order.getStatus() == Order.OrderStatus.CANCELLED ||
            order.getStatus() == Order.OrderStatus.ACCEPTED ||
            order.getStatus() == Order.OrderStatus.ARRIVED ||
            order.getStatus() == Order.OrderStatus.STARTED) {
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
                        userBooking.setFromLat(dto.getFromLat());
                        userBooking.setFromLon(dto.getFromLon());
                    }
                    userBooking.setPickupAddress(formattedPickupAddress);
                }
                if (dto.getToLat() != null) {
                    userBooking.setToLat(dto.getToLat());
                }
                if (dto.getToLon() != null) {
                    userBooking.setToLon(dto.getToLon());
                }
                if (dto.getNotes() != null) {
                    userBooking.setNotes(dto.getNotes());
                }
                rideBookingRepository.save(userBooking);

                // Update passenger's virtual order — notes only. dto.getFromAddress()/
                // getFromLat()/getFromLon() here is this passenger's PICKUP point
                // (already applied to userBooking above), not a change to the
                // route itself; writing it into passengerOrder's from/to fields
                // used to overwrite the route origin/destination shown in this
                // passenger's own order list with their street-level pickup
                // address the moment they set or edited a custom pickup.
                if (userBooking.getPassengerOrderId() != null) {
                    Order passengerOrder = orderRepository.findById(userBooking.getPassengerOrderId()).orElse(null);
                    if (passengerOrder != null) {
                        if (dto.getNotes() != null) passengerOrder.setNotes(dto.getNotes());
                        orderRepository.save(passengerOrder);
                    }
                }

                // WS ping to move the driver's live map, plus a dedicated
                // pickup-changed push instead of the generic status-update
                // one (misleading here since order.status hasn't changed).
                notificationService.notifyOrderStatusUpdate(order, true, false);
                notificationService.notifyPickupLocationChanged(order);

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
        } else if (savedOrder.getPassenger() != null && savedOrder.getDriver() == null) {
            // A passenger's still-PENDING request, edited before any driver
            // has offered on it — notifyOrderStatusUpdate above only reaches
            // this passenger themself, the (nonexistent) assigned driver,
            // and any existing driverOffers (none yet). Without this call,
            // drivers browsing/already viewing this request never learn the
            // route/time/etc. changed — confirmed as "editing gets no
            // notification to drivers at all" whether their app was open or
            // closed, since neither the WS broadcast nor the FCM push this
            // method sends ever fired for this case.
            notificationService.notifyPendingOrderUpdated(savedOrder);
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
                sameDepartureDate(departureDate, other.getDepartureDate())) {

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
                sameDepartureDate(departureDate, other.getDepartureDate())) {

                // Compare routes
                if (isRouteMatching(fromAddress, other.getFromAddress()) &&
                    isRouteMatching(toAddress, other.getToAddress())) {
                    return other;
                }
            }
        }
        return null;
    }

    private static final java.time.format.DateTimeFormatter DEPARTURE_DATE_DOT_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Compares two departureDate strings as calendar dates rather than raw text.
     * waygo_driver sends this field as "yyyy-MM-dd" while waygo_user sends "dd.MM.yyyy" —
     * a plain String.equals() silently never matched a driver's existing route
     * announcement against a passenger's request for the same real-world date, so
     * accepting an offer always created a duplicate announcement instead of merging
     * into the existing one.
     */
    private boolean sameDepartureDate(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        java.time.LocalDate dateA = parseDepartureDate(a);
        java.time.LocalDate dateB = parseDepartureDate(b);
        return dateA != null && dateA.equals(dateB);
    }

    private java.time.LocalDate parseDepartureDate(String value) {
        try {
            return java.time.LocalDate.parse(value); // "yyyy-MM-dd"
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.LocalDate.parse(value, DEPARTURE_DATE_DOT_FORMAT); // "dd.MM.yyyy"
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
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
        return getPendingOrders(null, null, null);
    }

    public List<Order> getPendingOrders(String region) {
        return getPendingOrders(region, null, null);
    }

    public List<Order> getPendingOrders(String region, Double lat, Double lon) {
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

        List<Order> result = orders;

        // Region is an explicit choice the driver made in the app's filter —
        // when set, it takes priority over proximity so a driver who wants a
        // specific region sees that region regardless of their current GPS
        // position. Nearest-region-centroid matching used to be applied
        // automatically instead of/alongside this, but that approximation
        // misclassified drivers/orders near a region or district border
        // (points get assigned to whichever centroid is closest, which
        // doesn't track the real administrative boundary), hiding orders
        // that were genuinely in the driver's own area. Proximity is now
        // handled purely by real distance below (driver's live GPS vs the
        // order's pickup coordinates), which doesn't have that failure mode.
        if (region != null && !region.trim().isEmpty() && !"Barchasi".equalsIgnoreCase(region.trim())) {
            List<Order> filtered = new java.util.ArrayList<>();
            for (Order order : result) {
                if (order.getFromAddress() != null &&
                    order.getFromAddress().toLowerCase().contains(region.toLowerCase().trim())) {
                    filtered.add(order);
                }
            }
            result = filtered;
        }

        // Nearest-first for drivers, using their last known live location —
        // continuously reported to DriverLocationCache while "Ish rejimi"
        // (online) is on, independent of any active trip. If we don't have a
        // live fix yet for this driver (e.g. just went online, no GPS sample
        // reported yet), leave the list in its existing order rather than
        // guessing.
        if (currentUser != null && currentUser.getRole() == User.Role.DRIVER) {
            com.waygo.backend.dto.order.DriverLocationPayload driverLoc = driverLocationCache.getByDriverId(currentUser.getId());
            if (driverLoc != null && driverLoc.getLatitude() != null && driverLoc.getLongitude() != null) {
                double driverLat = driverLoc.getLatitude();
                double driverLon = driverLoc.getLongitude();

                // Admin-configurable cutoff (SystemSettings.orderVisibilityRadiusKm,
                // 0 = no limit). Applied before sorting so a huge order list isn't
                // sorted needlessly, and so an order missing fromLat/fromLon (which
                // distanceKm treats as "infinitely far") is correctly excluded
                // rather than kept because it couldn't be measured.
                int radiusKm = com.waygo.backend.service.SystemSettingsService.getOrderVisibilityRadiusKmConfig();
                if (radiusKm > 0) {
                    List<Order> withinRadius = new java.util.ArrayList<>();
                    for (Order o : result) {
                        if (distanceKm(driverLat, driverLon, o.getFromLat(), o.getFromLon()) <= radiusKm) {
                            withinRadius.add(o);
                        }
                    }
                    result = withinRadius;
                }

                List<Order> sorted = new java.util.ArrayList<>(result);
                sorted.sort(java.util.Comparator.comparingDouble(o -> distanceKm(driverLat, driverLon, o.getFromLat(), o.getFromLon())));
                result = sorted;
            }
        }

        return result;
    }

    /** Haversine distance in km. Orders with a missing pickup point sort last. */
    private static double distanceKm(double lat1, double lon1, Double lat2, Double lon2) {
        if (lat2 == null || lon2 == null) {
            return Double.MAX_VALUE;
        }
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
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
        Double reqLat = null;
        Double reqLon = null;
        List<String> seatsToBook = new java.util.ArrayList<>();
        for (String seat : selectedSeats) {
            if (seat != null && seat.startsWith("PICKUP:")) {
                pickup = seat.substring(7);
                // Fallback: parse [LAT:...,LON:...] inside pickup string if present
                try {
                    if (pickup.contains("[LAT:") && pickup.contains("LON:")) {
                        int latIdx = pickup.indexOf("[LAT:");
                        int commaIdx = pickup.indexOf(",", latIdx);
                        int lonIdx = pickup.indexOf("LON:", commaIdx);
                        int endBracket = pickup.indexOf("]", lonIdx);
                        if (latIdx != -1 && commaIdx != -1 && lonIdx != -1 && endBracket != -1) {
                            String latStr = pickup.substring(latIdx + 5, commaIdx).trim();
                            String lonStr = pickup.substring(lonIdx + 4, endBracket).trim();
                            reqLat = Double.parseDouble(latStr);
                            reqLon = Double.parseDouble(lonStr);
                            pickup = pickup.substring(0, latIdx).trim();
                        }
                    }
                } catch (Exception e) {}
            } else if (seat != null && seat.startsWith("NOTES:")) {
                notes = seat.substring(6);
            } else if (seat != null && (seat.startsWith("LAT:") || seat.startsWith("FROM_LAT:"))) {
                try {
                    int colonIdx = seat.indexOf(':');
                    reqLat = Double.parseDouble(seat.substring(colonIdx + 1));
                } catch (Exception e) {}
            } else if (seat != null && (seat.startsWith("LON:") || seat.startsWith("FROM_LON:"))) {
                try {
                    int colonIdx = seat.indexOf(':');
                    reqLon = Double.parseDouble(seat.substring(colonIdx + 1));
                } catch (Exception e) {}
            } else if (seat != null) {
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

            // If no new seats are requested and we have a pickup address/coords, update existing non-rejected bookings
            if (!hasNewSeats && (!pickup.isEmpty() || reqLat != null)) {
                for (com.waygo.backend.entity.RideBooking b : existingBookings) {
                    if (!"REJECTED".equals(b.getStatus())) {
                        if (!pickup.isEmpty()) {
                            b.setPickupAddress(pickup);
                        }
                        if (reqLat != null) b.setFromLat(reqLat);
                        if (reqLon != null) b.setFromLon(reqLon);
                        if (!notes.isEmpty()) {
                            b.setNotes(notes);
                        }
                        rideBookingRepository.save(b);
                        
                        if (b.getPassengerOrderId() != null) {
                            Order passengerOrder = orderRepository.findById(b.getPassengerOrderId()).orElse(null);
                            if (passengerOrder != null) {
                                // Pickup refines WHERE on the route this passenger boards —
                                // it must never overwrite the passenger's own order record's
                                // fromAddress/fromLat/fromLon, which represent the actual
                                // route origin shown in their order list ("QAYERDAN"). That
                                // used to happen here, and a route like "Jizzax shahri ->
                                // Toshkent shahri" would silently start displaying the
                                // passenger's street-level pickup address instead. The
                                // pickup itself already lives on the booking (b.pickupAddress
                                // / b.fromLat / b.fromLon, set above) — nothing else needs it.
                                if (!notes.isEmpty()) passengerOrder.setNotes(notes);
                                orderRepository.save(passengerOrder);
                            }
                        }
                    }
                }
                Order savedOrder = orderRepository.save(order);
                // WS ping to move the driver's live map, plus a dedicated
                // pickup-changed push instead of the generic status-update
                // one (misleading here since order.status hasn't changed).
                notificationService.notifyOrderStatusUpdate(savedOrder, true, false);
                notificationService.notifyPickupLocationChanged(savedOrder);
                return savedOrder;
            }
        }

        // Check if passenger already has an ACCEPTED booking on this order — merge the requested seats to it!
        java.util.Optional<com.waygo.backend.entity.RideBooking> pendingBooking =
                rideBookingRepository.findFirstByOrderIdAndPassengerIdAndStatus(orderId, passenger.getId(), "ACCEPTED");
        if (pendingBooking.isPresent()) {
            com.waygo.backend.entity.RideBooking b = pendingBooking.get();

            // Merge seats — only the ones not already on this booking are "new"
            // and need to be validated/deducted against the order's real availability.
            List<String> newSeats = new java.util.ArrayList<>();
            for (String seat : seatsToBook) {
                if (!b.getSelectedSeats().contains(seat)) {
                    newSeats.add(seat);
                }
            }
            if (!newSeats.isEmpty()) {
                List<String> orderAvailableSeats = order.getAvailableSeats();
                for (String seat : newSeats) {
                    String mappedSeat = mapSeatIndexToLabel(seat);
                    if (orderAvailableSeats == null || !orderAvailableSeats.contains(mappedSeat)) {
                        throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
                    }
                }
                for (String seat : newSeats) {
                    b.getSelectedSeats().add(seat);
                    orderAvailableSeats.remove(mapSeatIndexToLabel(seat));
                }
            }
            if (!pickup.isEmpty()) {
                b.setPickupAddress(pickup);
            }
            if (reqLat != null) b.setFromLat(reqLat);
            if (reqLon != null) b.setFromLon(reqLon);
            if (!notes.isEmpty()) {
                b.setNotes(notes);
            }
            rideBookingRepository.save(b);
            
            if (b.getPassengerOrderId() != null) {
                Order passengerOrder = orderRepository.findById(b.getPassengerOrderId()).orElse(null);
                if (passengerOrder != null) {
                    // See the matching comment in the block above (no-new-seats
                    // branch): pickup must stay on the booking, never overwrite
                    // the passenger's own order record's route fields.
                    if (!notes.isEmpty()) passengerOrder.setNotes(notes);
                    orderRepository.save(passengerOrder);
                }
            }

            // Sync with driver's active announcement if present
            if (order.getPassenger() != null && order.getDriver() != null) {
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
                        List<String> dbNewSeats = new java.util.ArrayList<>();
                        for (String seat : seatsToBook) {
                            if (!db.getSelectedSeats().contains(seat)) {
                                dbNewSeats.add(seat);
                            }
                        }
                        if (!dbNewSeats.isEmpty()) {
                            List<String> announcementSeats = activeAnnouncement.getAvailableSeats();
                            for (String seat : dbNewSeats) {
                                String mappedSeat = mapSeatIndexToLabel(seat);
                                if (announcementSeats == null || !announcementSeats.contains(mappedSeat)) {
                                    throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
                                }
                            }
                            for (String seat : dbNewSeats) {
                                db.getSelectedSeats().add(seat);
                                announcementSeats.remove(mapSeatIndexToLabel(seat));
                            }
                        }
                        if (!pickup.isEmpty()) {
                            db.setPickupAddress(pickup);
                        }
                        if (reqLat != null) db.setFromLat(reqLat);
                        if (reqLon != null) db.setFromLon(reqLon);
                        if (!notes.isEmpty()) {
                            db.setNotes(notes);
                        }
                        rideBookingRepository.save(db);
                        orderRepository.save(activeAnnouncement);
                        notificationService.notifyOrderStatusUpdate(activeAnnouncement);
                        notificationService.notifySeatBookedByPassenger(activeAnnouncement, passenger);
                    }
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

        Double fromLat = reqLat != null ? reqLat : (matchingRequest != null ? matchingRequest.getFromLat() : (order.getPassenger() != null ? order.getFromLat() : null));
        Double fromLon = reqLon != null ? reqLon : (matchingRequest != null ? matchingRequest.getFromLon() : (order.getPassenger() != null ? order.getFromLon() : null));
        Double toLat = matchingRequest != null ? matchingRequest.getToLat() : (order.getPassenger() != null ? order.getToLat() : null);
        Double toLon = matchingRequest != null ? matchingRequest.getToLon() : (order.getPassenger() != null ? order.getToLon() : null);

        // Create a new RideBooking (works for both first-time and additional seat requests)
        com.waygo.backend.entity.RideBooking booking = com.waygo.backend.entity.RideBooking.builder()
                .order(order)
                .passenger(passenger)
                .selectedSeats(seatsToBook)
                .pickupAddress(resolvePickupAddress(matchingRequest != null ? matchingRequest : order, pickup))
                .fromLat(fromLat)
                .fromLon(fromLon)
                .toLat(toLat)
                .toLon(toLon)
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
                    List<String> announcementSeats = activeAnnouncement.getAvailableSeats();
                    for (String seat : seatsToBook) {
                        String mappedSeat = mapSeatIndexToLabel(seat);
                        if (announcementSeats == null || !announcementSeats.contains(mappedSeat)) {
                            throw new IllegalStateException("Tanlangan o'rindiqlardan biri yoki bir nechtasi mavjud emas yoki band qilingan.");
                        }
                    }

                    com.waygo.backend.entity.RideBooking autoBooking = com.waygo.backend.entity.RideBooking.builder()
                            .order(activeAnnouncement)
                            .passenger(passenger)
                            .selectedSeats(new java.util.ArrayList<>(seatsToBook))
                            .status("ACCEPTED")
                            .passengerOrderId(order.getId())
                            .pickupAddress(resolvePickupAddress(matchingRequest != null ? matchingRequest : order, pickup))
                            .fromLat(fromLat)
                            .fromLon(fromLon)
                            .toLat(toLat)
                            .toLon(toLon)
                            .notes(notes)
                            .createdAt(java.time.LocalDateTime.now())
                            .build();

                    rideBookingRepository.save(autoBooking);

                    // Auto-occupy seats for the active announcement immediately
                    for (String seat : seatsToBook) {
                        String mappedSeat = mapSeatIndexToLabel(seat);
                        announcementSeats.remove(mappedSeat);
                    }

                    activeAnnouncement.getBookings().add(autoBooking);
                    orderRepository.save(activeAnnouncement);
                    notificationService.notifyOrderStatusUpdate(activeAnnouncement);
                    notificationService.notifySeatBookedByPassenger(activeAnnouncement, passenger);
                }
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
            if (booking.getFromLat() != null) {
                targetAcceptedBooking.setFromLat(booking.getFromLat());
            }
            if (booking.getFromLon() != null) {
                targetAcceptedBooking.setFromLon(booking.getFromLon());
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

        // Notify the first uncollected passenger in sequence that their turn has arrived
        List<com.waygo.backend.entity.RideBooking> confirmBookings = rideBookingRepository.findByOrderId(savedOrder.getId());
        if (confirmBookings != null) {
            for (com.waygo.backend.entity.RideBooking b : confirmBookings) {
                if (b != null && !"COLLECTED".equalsIgnoreCase(b.getStatus()) && !"REJECTED".equalsIgnoreCase(b.getStatus()) && !"CANCELLED".equalsIgnoreCase(b.getStatus()) && b.getPassenger() != null) {
                    sendNextPassengerTurnNotification(b, driver, savedOrder.getId());
                    break;
                }
            }
        }

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
        List<com.waygo.backend.entity.RideBooking> collectBookings = rideBookingRepository.findByOrderId(savedOrder.getId());
        if (collectBookings != null) {
            for (com.waygo.backend.entity.RideBooking b : collectBookings) {
                if (b != null && !"COLLECTED".equalsIgnoreCase(b.getStatus()) && !"REJECTED".equalsIgnoreCase(b.getStatus()) && !"CANCELLED".equalsIgnoreCase(b.getStatus()) && b.getPassenger() != null) {
                    sendNextPassengerTurnNotification(b, driver, savedOrder.getId());
                    break; // Notify the next passenger in sequence
                }
            }
        }

        return savedOrder;
    }

    @Transactional
    public void notifyPassengerTurn(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can send pickup-turn notifications");
        }

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));
        Order order = booking.getOrder();
        if (order.getDriver() == null || !order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }

        sendNextPassengerTurnNotification(booking, driver, order.getId());
    }

    // Manual "I've arrived" trigger for a single route passenger — unlike the
    // solo-order ARRIVED status (which is a persisted Order state the driver
    // sets once), a route trip has one Order shared by several bookings, so
    // there's no single "arrived" status that would make sense for all of
    // them at once. The driver instead presses this per booking, right as
    // they reach that specific passenger's pickup point.
    @Transactional
    public void notifyBookingArrived(Long bookingId) {
        User driver = securityUtils.getCurrentUser();
        if (driver == null || driver.getRole() != User.Role.DRIVER) {
            throw new UnauthorizedAccessException("Only drivers can send an arrival notification");
        }

        com.waygo.backend.entity.RideBooking booking = rideBookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with id: " + bookingId));
        Order order = booking.getOrder();
        if (order.getDriver() == null || !order.getDriver().getId().equals(driver.getId())) {
            throw new UnauthorizedAccessException("You are not the driver of this ride offer");
        }
        if (booking.getPassenger() == null) {
            return;
        }

        notificationService.notifyBookingArrived(booking.getPassenger(), driver, order.getId());
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

        if (order.getStatus() == Order.OrderStatus.STARTED || order.getStatus() == Order.OrderStatus.ARRIVED || order.getStatus() == Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("Safar boshlanganligi sababli bronni bekor qila olmaysiz.");
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

        if (order.getStatus() == Order.OrderStatus.STARTED || order.getStatus() == Order.OrderStatus.ARRIVED || order.getStatus() == Order.OrderStatus.COMPLETED) {
            throw new IllegalStateException("Safar boshlanganligi sababli buyurtmani bekor qila olmaysiz. Iltimos, haydovchi bilan bog'laning.");
        }

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
                        // pOrder.getStatus() is never touched here — only
                        // availableSeats/booking sub-fields are synced — and
                        // this runs once per booking on the announcement,
                        // called from 5 different driver actions
                        // (confirmBooking/collectBooking/uncollectBooking/
                        // cancelBooking/updateOrder). An unconditional full
                        // push meant every OTHER passenger on a shared
                        // carpool got a misleading "status updated" push
                        // every time the driver touched any ONE passenger's
                        // seat — confirmed as the dominant remaining source
                        // of "buyurtma holati yangilandi" spam reports.
                        // WebSocket-only keeps every passenger's UI in sync
                        // live without the repeated push.
                        notificationService.notifyOrderStatusUpdate(pOrder, true, false);
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

    /**
     * Priority: (1) an explicit "PICKUP:" token the driver just passed in
     * this call (fallbackPickup) — the most current input; (2) the
     * passenger's own saved Order.pickupAddress/pickupLat/pickupLon, set via
     * PATCH /orders/{id}/pickup-location — without this tier, a pickup point
     * the passenger deliberately fine-tuned before a driver confirmed their
     * offer was silently discarded and the booking fell back to the route's
     * plain fromAddress/fromLat/fromLon instead; (3) that route fallback
     * itself, for orders with no pickup ever set.
     */
    private String resolvePickupAddress(Order order, String fallbackPickup) {
        if (fallbackPickup != null && !fallbackPickup.trim().isEmpty()) {
            if (fallbackPickup.contains("[LAT:")) {
                return fallbackPickup;
            }
            if (order != null && order.getFromLat() != null && order.getFromLon() != null) {
                return String.format("%s [LAT:%s, LON:%s]", fallbackPickup, order.getFromLat(), order.getFromLon());
            }
            return fallbackPickup;
        }
        if (order != null && order.getPickupAddress() != null && !order.getPickupAddress().trim().isEmpty()
                && order.getPickupLat() != null && order.getPickupLon() != null) {
            String baseAddr = order.getPickupAddress();
            if (baseAddr.contains("[LAT:")) {
                return baseAddr;
            }
            return String.format("%s [LAT:%s, LON:%s]", baseAddr, order.getPickupLat(), order.getPickupLon());
        }
        if (order != null && order.getFromLat() != null && order.getFromLon() != null) {
            String baseAddr = order.getFromAddress() != null ? order.getFromAddress() : "";
            return String.format("%s [LAT:%s, LON:%s]", baseAddr, order.getFromLat(), order.getFromLon());
        }
        return fallbackPickup;
    }
}
