package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id")
    private User passenger;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private User driver;

    private String fromAddress;
    private String toAddress;
    
    private Double fromLat;
    private Double fromLon;
    private Double toLat;
    private Double toLon;

    private String departureDate;
    private String departureTime;
    private Integer passengerCount;
    private String notes;

    @Column(precision = 19, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @ElementCollection
    @CollectionTable(name = "order_seats", joinColumns = @JoinColumn(name = "order_id"))
    @Column(name = "seat_label")
    @Builder.Default
    private java.util.List<String> availableSeats = new java.util.ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("order")
    private java.util.List<RideBooking> bookings = new java.util.ArrayList<>();

    private String baggageDescription;
    private Boolean hasAirConditioning;
    private Boolean hasBaggage;
    private Boolean hasChildSeat;
    private Boolean hasTrailer;

    @Builder.Default
    private Boolean passengerConfirmed = false;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum OrderStatus {
        PENDING,    // Searching for driver
        ACCEPTED,   // Driver accepted
        ARRIVED,    // Driver arrived at pick-up
        STARTED,    // Trip started
        COMPLETED,  // Finished
        CANCELLED   // Cancelled by user or driver
    }
}
