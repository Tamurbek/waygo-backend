package com.waygo.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "ride_bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class RideBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnoreProperties({"bookings", "driver", "passenger", "hibernateLazyInitializer", "handler"})
    private Order order;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "passenger_id")
    private User passenger;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"))
    @Column(name = "seat_label")
    @Builder.Default
    @Fetch(FetchMode.SUBSELECT)
    private java.util.List<String> selectedSeats = new java.util.ArrayList<>();

    private String status; // "PENDING", "ACCEPTED", "REJECTED", "COLLECTED"

    @Column(name = "passenger_order_id")
    private Long passengerOrderId;

    private String pickupAddress;

    private Double fromLat;
    private Double fromLon;
    private Double toLat;
    private Double toLon;

    private String notes;

    // A booking is how a passenger participates in a shared driver-
    // announcement Order (Order.passenger is null on those — see
    // OrderService.rateDriver). Rating/comment must live here, not on the
    // shared Order, since several different passengers can each rate the
    // same order independently and a single Order-level field would let
    // them overwrite one another.
    private Double rating;
    private String comment;

    @ElementCollection
    @CollectionTable(name = "booking_feedback_tags", joinColumns = @JoinColumn(name = "booking_id"))
    @Column(name = "tag")
    @Builder.Default
    @Fetch(FetchMode.SUBSELECT)
    private java.util.List<String> feedbackTags = new java.util.ArrayList<>();

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

