package com.waygo.backend.repository;

import com.waygo.backend.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);
    List<Order> findByDriverIdOrderByCreatedAtDesc(Long driverId);
    List<Order> findByStatus(Order.OrderStatus status);
    long countByStatus(Order.OrderStatus status);
    List<Order> findTop10ByOrderByCreatedAtDesc();
}
