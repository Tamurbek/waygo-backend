package com.waygo.backend.service;

import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final TransactionService transactionService;

    @Transactional
    public Order createOrder(Long passengerId, String from, String to, BigDecimal price) {
        User passenger = userRepository.findById(passengerId)
                .orElseThrow(() -> new RuntimeException("Passenger not found"));

        Order order = Order.builder()
                .passenger(passenger)
                .fromAddress(from)
                .toAddress(to)
                .price(price)
                .status(Order.OrderStatus.PENDING)
                .build();

        return orderRepository.save(order);
    }

    @Transactional
    public Order acceptOrder(Long orderId, Long driverId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new RuntimeException("Driver not found"));

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new RuntimeException("Order is already accepted or cancelled");
        }

        order.setDriver(driver);
        order.setStatus(Order.OrderStatus.ACCEPTED);
        
        return orderRepository.save(order);
    }

    @Transactional
    public Order completeTrip(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (order.getStatus() != Order.OrderStatus.STARTED) {
            throw new RuntimeException("Cannot complete a trip that hasn't started");
        }

        // Process final payment automatically!
        transactionService.processPayment(order.getPassenger().getId(), order.getDriver().getId(), order.getPrice());

        order.setStatus(Order.OrderStatus.COMPLETED);
        return orderRepository.save(order);
    }

    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        
        order.setStatus(status);
        return orderRepository.save(order);
    }

    public List<Order> getPassengerHistory(Long passengerId) {
        return orderRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId);
    }

    public List<Order> getDriverHistory(Long driverId) {
        return orderRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
    }

    public List<Order> getPendingOrders() {
        return orderRepository.findByStatus(Order.OrderStatus.PENDING);
    }
}
