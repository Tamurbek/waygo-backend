package com.waygo.backend.repository;

import com.waygo.backend.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Modifying
    @Query("UPDATE Order o SET o.driver = null WHERE o.driver.id = :driverId")
    void clearDriverReferences(@Param("driverId") Long driverId);

    @Modifying
    @Query("UPDATE Order o SET o.passenger = null WHERE o.passenger.id = :passengerId")
    void clearPassengerReferences(@Param("passengerId") Long passengerId);

    @Query("SELECT o FROM Order o WHERE o.passenger.id = :passengerId OR o.id IN (SELECT b.order.id FROM RideBooking b WHERE b.passenger.id = :passengerId AND b.status != 'REJECTED') ORDER BY o.createdAt DESC")
    List<Order> findByPassengerIdOrderByCreatedAtDesc(@Param("passengerId") Long passengerId);

    List<Order> findByDriverIdOrderByCreatedAtDesc(Long driverId);
    List<Order> findByStatusAndDriverIsNull(Order.OrderStatus status);
    List<Order> findByStatusAndPassengerIsNull(Order.OrderStatus status);

    // Note: STARTED trips are matched via bookings with status ACCEPTED *or*
    // COLLECTED. By the time a driver's route order actually transitions to
    // STARTED (once every passenger has been picked up), every booking on it
    // is already COLLECTED, not ACCEPTED - an ACCEPTED-only filter here would
    // never match, silently hiding in-progress trips from the passenger.
    @Query("SELECT o FROM Order o WHERE (o.status = :pendingStatus AND o.passenger IS NULL) OR (o.status = :startedStatus AND o.id IN (SELECT b.order.id FROM RideBooking b WHERE b.passenger.id = :passengerId AND b.status IN ('ACCEPTED', 'COLLECTED'))) ORDER BY o.createdAt DESC")
    List<Order> findPendingAndActiveForPassenger(
        @Param("passengerId") Long passengerId,
        @Param("pendingStatus") Order.OrderStatus pendingStatus,
        @Param("startedStatus") Order.OrderStatus startedStatus
    );

    @Query("SELECT o FROM Order o JOIN o.driverOffers f WHERE f.driver.id = :driverId AND f.status = 'ACCEPTED' ORDER BY o.createdAt DESC")
    List<Order> findByAcceptedOfferDriverId(@Param("driverId") Long driverId);

    List<Order> findByStatus(Order.OrderStatus status);
    long countByStatus(Order.OrderStatus status);
    List<Order> findTop10ByOrderByCreatedAtDesc();
}
