package com.waygo.backend;

import com.waygo.backend.dto.order.OrderCreateDTO;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.RideBooking;
import com.waygo.backend.entity.User;
import com.waygo.backend.entity.DriverOffer;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.repository.DriverProfileRepository;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.RideBookingRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.security.SecurityUtils;
import com.waygo.backend.service.NotificationService;
import com.waygo.backend.service.OrderService;
import com.waygo.backend.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private NotificationService notificationService;

    @Mock
    private DriverProfileRepository driverProfileRepository;

    @Mock
    private RideBookingRepository rideBookingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.waygo.backend.service.ReferralService referralService;

    @InjectMocks
    private OrderService orderService;

    private User passenger;
    private User driver;
    private Order order;

    @BeforeEach
    void setUp() {
        passenger = User.builder()
                .id(1L)
                .phone("+998901234567")
                .fullName("Passenger John")
                .role(User.Role.PASSENGER)
                .balance(BigDecimal.valueOf(100000.00))
                .build();

        driver = User.builder()
                .id(2L)
                .phone("+998911234567")
                .fullName("Driver Smith")
                .role(User.Role.DRIVER)
                .balance(BigDecimal.ZERO)
                .build();

        order = Order.builder()
                .id(10L)
                .passenger(passenger)
                .fromAddress("Tashkent, Yunusobod")
                .toAddress("Samarqand, Vokzal")
                .price(BigDecimal.valueOf(50000))
                .passengerCount(2)
                .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT", "BACK_CENTER", "BACK_RIGHT")))
                .status(Order.OrderStatus.PENDING)
                .build();
    }

    @Test
    void testCreatePassengerOrder_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setFromAddress("Tashkent, Yunusobod");
        dto.setToAddress("Samarqand, Vokzal");
        dto.setPrice(BigDecimal.valueOf(50000));
        dto.setPassengerCount(2);
        dto.setNotes("Need quiet ride");

        Order created = orderService.createOrder(dto);

        assertNotNull(created);
        assertEquals(passenger, created.getPassenger());
        assertNull(created.getDriver());
        assertEquals(Order.OrderStatus.PENDING, created.getStatus());
        assertEquals("Need quiet ride", created.getNotes());
        verify(orderRepository).save(any(Order.class));
        verify(notificationService).notifyNewOrder(any(Order.class));
    }

    @Test
    void testCreateDriverOfferOrder_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setFromAddress("Tashkent, Chilanzar");
        dto.setToAddress("Jizzax, Ota-Yurt");
        dto.setPrice(BigDecimal.valueOf(40000));
        dto.setPassengerCount(4);
        dto.setAvailableSeats(Arrays.asList("FRONT", "BACK_LEFT"));

        Order created = orderService.createOrder(dto);

        assertNotNull(created);
        assertEquals(driver, created.getDriver());
        assertNull(created.getPassenger());
        assertEquals(Order.OrderStatus.PENDING, created.getStatus());
        verify(orderRepository).save(any(Order.class));
        verify(notificationService).notifyNewOrder(any(Order.class));
    }

    @Test
    void testLockOrder_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order locked = orderService.lockOrder(10L);

        assertNotNull(locked);
        assertEquals(driver.getId(), locked.getLockedByDriverId());
        assertNotNull(locked.getLockExpirationTime());
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testLockOrder_AlreadyLockedByOtherDriverThrowsException() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);
        
        Order lockedOrder = Order.builder()
                .id(10L)
                .passenger(passenger)
                .status(Order.OrderStatus.PENDING)
                .lockedByDriverId(99L)
                .lockExpirationTime(LocalDateTime.now().plusSeconds(20))
                .build();
                
        when(orderRepository.findById(10L)).thenReturn(Optional.of(lockedOrder));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> orderService.lockOrder(10L));
        assertTrue(ex.getMessage().contains("boshqa haydovchi tomonidan ko'rib chiqilmoqda"));
    }

    @Test
    void testUnlockOrder_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);
        
        order.setLockedByDriverId(driver.getId());
        order.setLockExpirationTime(LocalDateTime.now().plusSeconds(25));
        
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order unlocked = orderService.unlockOrder(10L);

        assertNotNull(unlocked);
        assertNull(unlocked.getLockedByDriverId());
        assertNull(unlocked.getLockExpirationTime());
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
        verify(notificationService).notifyNewOrder(any(Order.class));
    }

    @Test
    void testAcceptOrder_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order accepted = orderService.acceptOrder(10L, null);

        assertNotNull(accepted);
        assertNull(accepted.getDriver());
        assertEquals(Order.OrderStatus.PENDING, accepted.getStatus());
        assertEquals(1, accepted.getDriverOffers().size());
        assertEquals(driver, accepted.getDriverOffers().get(0).getDriver());
        assertEquals("PENDING", accepted.getDriverOffers().get(0).getStatus());
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testConfirmDriver_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);
        
        order.setDriver(driver);
        order.setStatus(Order.OrderStatus.ACCEPTED);
        
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order confirmed = orderService.confirmDriver(10L);

        assertNotNull(confirmed);
        assertTrue(confirmed.getPassengerConfirmed());
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testConfirmDriverOffer_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);

        // Setup passenger request order
        order.setDriverOffers(new ArrayList<>());
        order.setBookings(new ArrayList<>());
        order.setDepartureDate("28.05.2026");
        order.setFromAddress("Tashkent, Yunusobod");
        order.setToAddress("Samarqand, Vokzal");
        
        DriverOffer offer = DriverOffer.builder()
                .id(50L)
                .order(order)
                .driver(driver)
                .pricePerPerson(BigDecimal.valueOf(60000))
                .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT")))
                .status("PENDING")
                .build();
        order.getDriverOffers().add(offer);

        // Setup existing driver announcement order with slightly different route address strings (which should match)
        Order activeAnnouncement = Order.builder()
                .id(200L)
                .driver(driver)
                .passenger(null) // driver announcement
                .fromAddress("Tashkent, Chilanzar")
                .toAddress("Samarqand, Markaz")
                .departureDate("28.05.2026")
                .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT", "BACK_CENTER", "BACK_RIGHT")))
                .bookings(new ArrayList<>())
                .status(Order.OrderStatus.PENDING)
                .build();

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId()))
                .thenReturn(Arrays.asList(activeAnnouncement, order));
        when(rideBookingRepository.save(any(RideBooking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> selectedSeats = Arrays.asList("1"); // FRONT
        Order confirmed = orderService.confirmDriverOffer(10L, 50L, selectedSeats);

        assertNotNull(confirmed);
        assertEquals(Order.OrderStatus.ACCEPTED, confirmed.getStatus());
        assertEquals(driver, confirmed.getDriver());
        assertTrue(confirmed.getPassengerConfirmed());
        assertEquals("ACCEPTED", offer.getStatus());

        // Verify booking created on active announcement
        assertEquals(1, activeAnnouncement.getBookings().size());
        // Verify FRONT seat (mapped from "1") was removed from active announcement's availableSeats
        assertFalse(activeAnnouncement.getAvailableSeats().contains("FRONT"));
        assertTrue(activeAnnouncement.getAvailableSeats().contains("BACK_LEFT"));

        verify(rideBookingRepository, times(1)).save(any(RideBooking.class));
        verify(orderRepository, times(2)).save(any(Order.class)); // 1 for order, 1 for updated announcement
        verify(notificationService, atLeastOnce()).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testRejectDriver_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);
        
        order.setDriver(driver);
        order.setStatus(Order.OrderStatus.ACCEPTED);
        
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order rejected = orderService.rejectDriver(10L);

        assertNotNull(rejected);
        assertNull(rejected.getDriver());
        assertEquals(Order.OrderStatus.PENDING, rejected.getStatus());
        assertFalse(rejected.getPassengerConfirmed());
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
        verify(notificationService).notifyNewOrder(any(Order.class));
    }

    @Test
    void testBookRide_WithNotesAndPickup_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);
        
        Order driverOffer = Order.builder()
                .id(20L)
                .driver(driver)
                .fromAddress("Tashkent")
                .toAddress("Samarqand")
                .price(BigDecimal.valueOf(60000))
                .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT")))
                .status(Order.OrderStatus.PENDING)
                .bookings(new ArrayList<>())
                .build();
                
        when(orderRepository.findById(20L)).thenReturn(Optional.of(driverOffer));
        when(rideBookingRepository.findByOrderIdAndPassengerId(20L, passenger.getId())).thenReturn(new ArrayList<>());
        when(rideBookingRepository.findFirstByOrderIdAndPassengerIdAndStatus(20L, passenger.getId(), "PENDING")).thenReturn(Optional.empty());
        when(rideBookingRepository.save(any(RideBooking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> selection = Arrays.asList("1", "PICKUP:Chilonzor metro", "NOTES:Have massive box");
        Order booked = orderService.bookRide(20L, selection);

        assertNotNull(booked);
        assertEquals(1, booked.getBookings().size());
        
        RideBooking booking = booked.getBookings().get(0);
        assertEquals(passenger, booking.getPassenger());
        assertEquals(Arrays.asList("1"), booking.getSelectedSeats());
        assertEquals("Chilonzor metro", booking.getPickupAddress());
        assertEquals("Have massive box", booking.getNotes());
        assertEquals("PENDING", booking.getStatus());
        
        verify(rideBookingRepository).save(any(RideBooking.class));
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testConfirmBooking_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order offer = Order.builder()
                .id(20L)
                .driver(driver)
                .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT")))
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(offer)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1")) // FRONT
                .status("PENDING")
                .build();

        when(rideBookingRepository.findById(55L)).thenReturn(Optional.of(booking));
        when(rideBookingRepository.save(any(RideBooking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order confirmedOffer = orderService.confirmBooking(55L);

        assertNotNull(confirmedOffer);
        assertEquals("ACCEPTED", booking.getStatus());
        // Available seats should have removed "FRONT" (mapped from "1")
        assertFalse(confirmedOffer.getAvailableSeats().contains("FRONT"));
        assertTrue(confirmedOffer.getAvailableSeats().contains("BACK_LEFT"));
        verify(rideBookingRepository).save(booking);
        verify(orderRepository).save(offer);
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testRejectBooking_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order offer = Order.builder()
                .id(20L)
                .driver(driver)
                .availableSeats(new ArrayList<>(Arrays.asList("BACK_LEFT")))
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(offer)
                .passenger(passenger)
                .selectedSeats(new ArrayList<>(Arrays.asList("1"))) // FRONT was booked previously
                .status("ACCEPTED")
                .build();

        when(rideBookingRepository.findById(55L)).thenReturn(Optional.of(booking));
        when(rideBookingRepository.save(any(RideBooking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId())).thenReturn(new ArrayList<>());

        Order rejectedOffer = orderService.rejectBooking(55L, null);

        assertNotNull(rejectedOffer);
        assertEquals("REJECTED", booking.getStatus());
        // Since it was ACCEPTED, rejecting should release "FRONT" (mapped from "1") back into availability
        assertTrue(rejectedOffer.getAvailableSeats().contains("FRONT"));
        verify(rideBookingRepository).save(booking);
    }

    @Test
    void testRejectBooking_DriverRejects_RevertsPassengerOriginalRequestToPending() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order driverAnnouncement = Order.builder()
                .id(20L)
                .driver(driver)
                .fromAddress("Jizzax")
                .toAddress("Toshkent")
                .departureDate("28.05.2026")
                .availableSeats(new ArrayList<>())
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(driverAnnouncement)
                .passenger(passenger)
                .selectedSeats(new ArrayList<>(Arrays.asList("1")))
                .status("ACCEPTED")
                .build();

        Order passengerRequest = Order.builder()
                .id(100L)
                .passenger(passenger)
                .driver(driver)
                .fromAddress("Jizzax")
                .toAddress("Toshkent")
                .departureDate("28.05.2026")
                .status(Order.OrderStatus.ACCEPTED)
                .passengerConfirmed(true)
                .bookings(new ArrayList<>(Arrays.asList(
                    RideBooking.builder().status("ACCEPTED").passenger(passenger).build()
                )))
                .availableSeats(new ArrayList<>())
                .build();

        when(rideBookingRepository.findById(55L)).thenReturn(Optional.of(booking));
        when(rideBookingRepository.save(any(RideBooking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId()))
                .thenReturn(new ArrayList<>(Arrays.asList(passengerRequest)));

        Order result = orderService.rejectBooking(55L, null);

        assertNotNull(result);
        assertEquals("REJECTED", booking.getStatus());
        
        // Assert that the passenger's original request order is reverted back to PENDING!
        assertNull(passengerRequest.getDriver());
        assertEquals(Order.OrderStatus.PENDING, passengerRequest.getStatus());
        assertFalse(passengerRequest.getPassengerConfirmed());
        assertEquals("REJECTED", passengerRequest.getBookings().get(0).getStatus());
    }

    @Test
    void testRejectBooking_DriverRejects_RevertsPassengerOriginalRequestToPending_WithSlightlyDifferentAddresses() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order driverAnnouncement = Order.builder()
                .id(20L)
                .driver(driver)
                .fromAddress("Jizzax viloyati, Markaz")
                .toAddress("Toshkent shahar, Yunusobod")
                .departureDate("28.05.2026")
                .availableSeats(new ArrayList<>())
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(driverAnnouncement)
                .passenger(passenger)
                .selectedSeats(new ArrayList<>(Arrays.asList("1")))
                .status("ACCEPTED")
                .build();

        Order passengerRequest = Order.builder()
                .id(100L)
                .passenger(passenger)
                .driver(driver)
                .fromAddress("Jizzax, Toshkent ko'chasi")
                .toAddress("Toshkent, Sergeli")
                .departureDate("28.05.2026")
                .status(Order.OrderStatus.ACCEPTED)
                .passengerConfirmed(true)
                .bookings(new ArrayList<>(Arrays.asList(
                    RideBooking.builder().status("ACCEPTED").passenger(passenger).build()
                )))
                .availableSeats(new ArrayList<>())
                .build();

        when(rideBookingRepository.findById(55L)).thenReturn(Optional.of(booking));
        when(rideBookingRepository.save(any(RideBooking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId()))
                .thenReturn(new ArrayList<>(Arrays.asList(passengerRequest)));

        Order result = orderService.rejectBooking(55L, null);

        assertNotNull(result);
        assertEquals("REJECTED", booking.getStatus());
        
        // Assert that the passenger's original request order is reverted back to PENDING!
        assertNull(passengerRequest.getDriver());
        assertEquals(Order.OrderStatus.PENDING, passengerRequest.getStatus());
        assertFalse(passengerRequest.getPassengerConfirmed());
        assertEquals("REJECTED", passengerRequest.getBookings().get(0).getStatus());
    }

    @Test
    void testCancelBooking_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);

        Order offer = Order.builder()
                .id(20L)
                .driver(driver)
                .availableSeats(new ArrayList<>(Arrays.asList("BACK_LEFT")))
                .bookings(new ArrayList<>())
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(offer)
                .passenger(passenger)
                .selectedSeats(new ArrayList<>(Arrays.asList("1"))) // FRONT
                .status("ACCEPTED")
                .build();
        offer.getBookings().add(booking);

        when(rideBookingRepository.findById(55L)).thenReturn(Optional.of(booking));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order cancelledOffer = orderService.cancelBooking(55L, null);

        assertNotNull(cancelledOffer);
        // Booking should be removed from the offer's bookings list
        assertTrue(cancelledOffer.getBookings().isEmpty());
        // Since it was ACCEPTED, cancelling should release "FRONT" back
        assertTrue(cancelledOffer.getAvailableSeats().contains("FRONT"));
        verify(rideBookingRepository).delete(booking);
    }

    @Test
    void testCompleteTrip_Success() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);
        
        order.setDriver(driver);
        order.setStatus(Order.OrderStatus.STARTED);

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order completed = orderService.completeTrip(10L);

        assertNotNull(completed);
        assertEquals(Order.OrderStatus.COMPLETED, completed.getStatus());
        verify(transactionService).processPayment(passenger.getId(), driver.getId(), order.getPrice());
        verify(notificationService).notifyOrderStatusUpdate(any(Order.class));
    }

    @Test
    void testUpdateOrder_PreservesExistingNotesOnNullNotes() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);
        
        order.setNotes("Existing passenger notes");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setFromAddress("Tashkent, Buyuk Ipak Yuli");
        dto.setNotes(null); // Keep notes null to test safeguard

        Order updated = orderService.updateOrder(10L, dto);

        assertNotNull(updated);
        assertEquals("Tashkent, Buyuk Ipak Yuli", updated.getFromAddress());
        assertEquals("Existing passenger notes", updated.getNotes()); // Should not be overwritten with empty or null
    }

    @Test
    void testGetPendingOrders_FiltersByRegion() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order orderTashkent = Order.builder()
                .id(101L)
                .fromAddress("Tashkent sh., Yunusobod")
                .status(Order.OrderStatus.PENDING)
                .build();

        Order orderSamarkand = Order.builder()
                .id(102L)
                .fromAddress("Samarqand vil., Markaz")
                .status(Order.OrderStatus.PENDING)
                .build();

        when(orderRepository.findByStatusAndDriverIsNull(Order.OrderStatus.PENDING))
                .thenReturn(Arrays.asList(orderTashkent, orderSamarkand));

        List<Order> result = orderService.getPendingOrders("Tashkent");

        assertEquals(1, result.size());
        assertEquals(101L, result.get(0).getId());
    }

    @Test
    void testGetDriverHistory_DeduplicatesPassengerRequests() {
        Order announcement = Order.builder()
                .id(101L)
                .driver(driver)
                .passenger(null) // driver announcement
                .fromAddress("Tashkent, Markaz")
                .toAddress("Samarqand, Vokzal")
                .departureDate("28.05.2026")
                .status(Order.OrderStatus.PENDING)
                .build();

        Order passengerRequest = Order.builder()
                .id(102L)
                .driver(driver)
                .passenger(passenger)
                .fromAddress("Tashkent, Markaz")
                .toAddress("Samarqand, Vokzal")
                .departureDate("28.05.2026")
                .status(Order.OrderStatus.ACCEPTED)
                .build();

        // When finding active announcements for same route, it should return our active announcement
        when(orderRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId()))
                .thenReturn(Arrays.asList(announcement, passengerRequest));
        when(orderRepository.findByAcceptedOfferDriverId(driver.getId()))
                .thenReturn(new ArrayList<>());

        List<Order> history = orderService.getDriverHistory(driver.getId());

        // Deduplication should filter out passengerRequest because announcement is active on same route & date
        assertEquals(1, history.size());
        assertEquals(101L, history.get(0).getId());
    }

    @Test
    void testCompleteTrip_DriverAnnouncement_ProcessesPaymentsAndPassengerOrders() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order announcement = Order.builder()
                .id(101L)
                .driver(driver)
                .passenger(null)
                .price(BigDecimal.valueOf(25000))
                .status(Order.OrderStatus.ACCEPTED)
                .bookings(new ArrayList<>())
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(announcement)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1", "2")) // two seats booked
                .status("ACCEPTED")
                .build();
        announcement.getBookings().add(booking);

        Order passengerRequest = Order.builder()
                .id(102L)
                .driver(driver)
                .passenger(passenger)
                .status(Order.OrderStatus.ACCEPTED)
                .build();

        when(orderRepository.findById(101L)).thenReturn(Optional.of(announcement));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId()))
                .thenReturn(Arrays.asList(passengerRequest));

        Order completed = orderService.completeTrip(101L);

        assertNotNull(completed);
        assertEquals(Order.OrderStatus.COMPLETED, completed.getStatus());
        // Verify payment is processed (25000 * 2 = 50000)
        verify(transactionService).processPayment(passenger.getId(), driver.getId(), BigDecimal.valueOf(50000));
        // Verify the linked passenger request order is also completed
        assertEquals(Order.OrderStatus.COMPLETED, passengerRequest.getStatus());
    }

    @Test
    void testUpdateStatus_CancelDriverAnnouncement_ReleasesPassengerRequests() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order announcement = Order.builder()
                .id(101L)
                .driver(driver)
                .passenger(null)
                .status(Order.OrderStatus.ACCEPTED)
                .bookings(new ArrayList<>())
                .build();

        RideBooking booking = RideBooking.builder()
                .id(55L)
                .order(announcement)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1"))
                .status("ACCEPTED")
                .build();
        announcement.getBookings().add(booking);

        Order passengerRequest = Order.builder()
                .id(102L)
                .driver(driver)
                .passenger(passenger)
                .status(Order.OrderStatus.ACCEPTED)
                .build();

        when(orderRepository.findById(101L)).thenReturn(Optional.of(announcement));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId()))
                .thenReturn(Arrays.asList(passengerRequest));

        Order cancelled = orderService.updateStatus(101L, Order.OrderStatus.CANCELLED);

        assertNotNull(cancelled);
        assertEquals(Order.OrderStatus.CANCELLED, cancelled.getStatus());
        assertEquals("REJECTED", booking.getStatus());
        // Passenger request order should be cancelled and driver set to null
        assertEquals(Order.OrderStatus.CANCELLED, passengerRequest.getStatus());
        assertNull(passengerRequest.getDriver());
        assertFalse(passengerRequest.getPassengerConfirmed());
    }

    @Test
    void testRejectDriver_CleansUpBookings() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);
        
        Order passengerRequest = Order.builder()
                .id(10L)
                .passenger(passenger)
                .driver(driver)
                .status(Order.OrderStatus.ACCEPTED)
                .passengerConfirmed(true)
                .bookings(new ArrayList<>())
                .build();

        Order driverAnnouncement = Order.builder()
                .id(200L)
                .driver(driver)
                .passenger(null)
                .status(Order.OrderStatus.PENDING)
                .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT")))
                .bookings(new ArrayList<>())
                .build();

        RideBooking pBooking = RideBooking.builder()
                .id(30L)
                .order(passengerRequest)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1"))
                .status("ACCEPTED")
                .passengerOrderId(10L)
                .build();
        passengerRequest.getBookings().add(pBooking);

        RideBooking dBooking = RideBooking.builder()
                .id(31L)
                .order(driverAnnouncement)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1"))
                .status("ACCEPTED")
                .passengerOrderId(10L)
                .build();
        driverAnnouncement.getBookings().add(dBooking);

        when(orderRepository.findById(10L)).thenReturn(Optional.of(passengerRequest));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rideBookingRepository.findByPassengerOrderId(10L)).thenReturn(Arrays.asList(pBooking, dBooking));

        Order result = orderService.rejectDriver(10L);

        assertNotNull(result);
        assertEquals(Order.OrderStatus.PENDING, result.getStatus());
        assertNull(result.getDriver());
        assertFalse(result.getPassengerConfirmed());
        
        verify(rideBookingRepository, times(2)).delete(any(RideBooking.class));
        // Check seats are restored in driverAnnouncement
        assertTrue(driverAnnouncement.getAvailableSeats().contains("FRONT"));
    }

    @Test
    void testCancelBooking_RevertsPassengerOrder() {
        when(securityUtils.getCurrentUser()).thenReturn(passenger);

        Order passengerRequest = Order.builder()
                .id(10L)
                .passenger(passenger)
                .driver(driver)
                .status(Order.OrderStatus.ACCEPTED)
                .passengerConfirmed(true)
                .bookings(new ArrayList<>())
                .build();

        Order driverAnnouncement = Order.builder()
                .id(200L)
                .driver(driver)
                .passenger(null)
                .status(Order.OrderStatus.PENDING)
                .availableSeats(new ArrayList<>(Arrays.asList("BACK_LEFT")))
                .bookings(new ArrayList<>())
                .build();

        RideBooking pBooking = RideBooking.builder()
                .id(30L)
                .order(passengerRequest)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1"))
                .status("ACCEPTED")
                .passengerOrderId(10L)
                .build();
        passengerRequest.getBookings().add(pBooking);

        RideBooking dBooking = RideBooking.builder()
                .id(31L)
                .order(driverAnnouncement)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1"))
                .status("ACCEPTED")
                .passengerOrderId(10L)
                .build();
        driverAnnouncement.getBookings().add(dBooking);

        when(rideBookingRepository.findById(31L)).thenReturn(Optional.of(dBooking));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(passengerRequest));
        when(rideBookingRepository.findByPassengerOrderId(10L)).thenReturn(Arrays.asList(dBooking, pBooking));

        Order result = orderService.cancelBooking(31L, null);

        assertNotNull(result);
        verify(rideBookingRepository, times(2)).delete(any(RideBooking.class));
        assertEquals(Order.OrderStatus.PENDING, passengerRequest.getStatus());
        assertNull(passengerRequest.getDriver());
        assertFalse(passengerRequest.getPassengerConfirmed());
        assertTrue(driverAnnouncement.getAvailableSeats().contains("FRONT"));
    }

    @Test
    void testUpdateStatus_SyncsPassengerOrderStatus() {
        when(securityUtils.getCurrentUser()).thenReturn(driver);

        Order driverAnnouncement = Order.builder()
                .id(200L)
                .driver(driver)
                .passenger(null)
                .status(Order.OrderStatus.ACCEPTED)
                .bookings(new ArrayList<>())
                .build();

        Order passengerRequest = Order.builder()
                .id(10L)
                .passenger(passenger)
                .driver(driver)
                .status(Order.OrderStatus.ACCEPTED)
                .build();

        RideBooking booking = RideBooking.builder()
                .id(31L)
                .order(driverAnnouncement)
                .passenger(passenger)
                .selectedSeats(Arrays.asList("1"))
                .status("ACCEPTED")
                .passengerOrderId(10L)
                .build();
        driverAnnouncement.getBookings().add(booking);

        when(orderRepository.findById(200L)).thenReturn(Optional.of(driverAnnouncement));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(passengerRequest));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order updatedAnnouncement = orderService.updateStatus(200L, Order.OrderStatus.ARRIVED);

        assertNotNull(updatedAnnouncement);
        assertEquals(Order.OrderStatus.ARRIVED, updatedAnnouncement.getStatus());
        assertEquals(Order.OrderStatus.ARRIVED, passengerRequest.getStatus());
        verify(orderRepository).save(passengerRequest);
    }

    @Test
    void testGetPassengerHistory_FiltersDuplicates() {
        Order passengerRequest = Order.builder()
                .id(10L)
                .passenger(passenger)
                .status(Order.OrderStatus.ACCEPTED)
                .build();

        Order driverAnnouncement = Order.builder()
                .id(200L)
                .driver(driver)
                .passenger(null)
                .status(Order.OrderStatus.PENDING)
                .bookings(new ArrayList<>())
                .build();

        RideBooking booking = RideBooking.builder()
                .id(31L)
                .order(driverAnnouncement)
                .passenger(passenger)
                .status("ACCEPTED")
                .passengerOrderId(10L)
                .build();
        driverAnnouncement.getBookings().add(booking);

        when(orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId()))
                .thenReturn(Arrays.asList(passengerRequest, driverAnnouncement));

        List<Order> result = orderService.getPassengerHistory(passenger.getId());

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getId()); // Only passenger request order is returned
    }
}

