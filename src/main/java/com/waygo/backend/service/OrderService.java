package com.waygo.backend.service;

import com.waygo.backend.dto.order.OrderCreateDTO;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.User;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.exception.UnauthorizedAccessException;
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

    @Transactional
    public Order createOrder(OrderCreateDTO dto) {
        User passenger = securityUtils.getCurrentUser();
        if (passenger == null) {
            throw new UnauthorizedAccessException("You must be logged in to create an order");
        }

        Order order = Order.builder()
                .passenger(passenger)
                .fromAddress(dto.getFromAddress())
                .toAddress(dto.getToAddress())
                .fromLat(dto.getFromLat())
                .fromLon(dto.getFromLon())
                .toLat(dto.getToLat())
                .toLon(dto.getToLon())
                .departureDate(dto.getDepartureDate())
                .departureTime(dto.getDepartureTime())
                .passengerCount(dto.getPassengerCount())
                .notes(dto.getNotes())
                .price(dto.getPrice())
                .status(Order.OrderStatus.PENDING)
                .build();

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

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("Order is no longer available for acceptance");
        }

        order.setDriver(driver);
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

        // Process final payment automatically
        transactionService.processPayment(order.getPassenger().getId(), order.getDriver().getId(), order.getPrice());

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
        if (currentUser == null || (!currentUser.getId().equals(order.getDriver().getId()) && !currentUser.getId().equals(order.getPassenger().getId()))) {
            throw new UnauthorizedAccessException("You are not part of this order");
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
        if (currentUser == null || !currentUser.getId().equals(order.getPassenger().getId())) {
            throw new UnauthorizedAccessException("You can only edit your own orders");
        }

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new IllegalStateException("You can only edit orders that are still pending");
        }

        order.setFromAddress(dto.getFromAddress());
        order.setToAddress(dto.getToAddress());
        order.setFromLat(dto.getFromLat());
        order.setFromLon(dto.getFromLon());
        order.setToLat(dto.getToLat());
        order.setToLon(dto.getToLon());
        order.setDepartureDate(dto.getDepartureDate());
        order.setDepartureTime(dto.getDepartureTime());
        order.setPassengerCount(dto.getPassengerCount());
        order.setNotes(dto.getNotes());
        order.setPrice(dto.getPrice());

        return orderRepository.save(order);
    }

    public List<Order> getPendingOrders() {
        return orderRepository.findByStatus(Order.OrderStatus.PENDING);
    }
}
