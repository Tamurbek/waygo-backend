package com.waygo.backend.repository;

import com.waygo.backend.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT o FROM Order o WHERE o.passenger.id = :passengerId OR o.id IN (SELECT b.order.id FROM RideBooking b WHERE b.passenger.id = :passengerId AND b.status != 'REJECTED') ORDER BY o.createdAt DESC")
    List<Order> findByPassengerIdOrderByCreatedAtDesc(@Param("passengerId") Long passengerId);

    List<Order> findByDriverIdOrderByCreatedAtDesc(Long driverId);
    List<Order> findByStatusAndDriverIsNull(Order.OrderStatus status);
    List<Order> findByStatusAndPassengerIsNull(Order.OrderStatus status);

    @Query("SELECT o FROM Order o WHERE (o.status = :pendingStatus AND o.passenger IS NULL) OR (o.status = :startedStatus AND o.id IN (SELECT b.order.id FROM RideBooking b WHERE b.passenger.id = :passengerId AND b.status = 'ACCEPTED')) ORDER BY o.createdAt DESC")
    List<Order> findPendingAndActiveForPassenger(
        @Param("passengerId") Long passengerId,
        @Param("pendingStatus") Order.OrderStatus pendingStatus,
        @Param("startedStatus") Order.OrderStatus startedStatus
    );

    List<Order> findByStatus(Order.OrderStatus status);
    long countByStatus(Order.OrderStatus status);
    List<Order> findTop10ByOrderByCreatedAtDesc();
}
