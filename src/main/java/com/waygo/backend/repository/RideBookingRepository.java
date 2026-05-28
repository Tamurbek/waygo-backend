package com.waygo.backend.repository;

import com.waygo.backend.entity.RideBooking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RideBookingRepository extends JpaRepository<RideBooking, Long> {
    List<RideBooking> findByOrderId(Long orderId);
    List<RideBooking> findByPassengerId(Long passengerId);
    List<RideBooking> findByOrderIdAndPassengerId(Long orderId, Long passengerId);
    java.util.Optional<RideBooking> findFirstByOrderIdAndPassengerIdAndStatus(Long orderId, Long passengerId, String status);
    List<RideBooking> findByPassengerOrderId(Long passengerOrderId);
}

