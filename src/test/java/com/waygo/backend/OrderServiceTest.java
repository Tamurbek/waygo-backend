package com.waygo.backend;

import com.waygo.backend.dto.order.OrderCreateDTO;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.RideBooking;
import com.waygo.backend.entity.User;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.repository.DriverProfileRepository;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.RideBookingRepository;
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
        assertEquals(driver, accepted.getDriver());
        assertEquals(Order.OrderStatus.ACCEPTED, accepted.getStatus());
        assertFalse(accepted.getPassengerConfirmed());
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

        Order rejectedOffer = orderService.rejectBooking(55L, null);

        assertNotNull(rejectedOffer);
        assertEquals("REJECTED", booking.getStatus());
        // Since it was ACCEPTED, rejecting should release "FRONT" (mapped from "1") back into availability
        assertTrue(rejectedOffer.getAvailableSeats().contains("FRONT"));
        verify(rideBookingRepository).save(booking);
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
}
