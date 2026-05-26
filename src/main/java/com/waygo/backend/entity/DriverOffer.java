package com.waygo.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "driver_offers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnoreProperties({"bookings", "driverOffers", "passenger", "driver", "hibernateLazyInitializer", "handler"})
    private Order order;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "driver_id")
    private User driver;

    @Column(precision = 19, scale = 4)
    private BigDecimal pricePerPerson;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "driver_offer_seats", joinColumns = @JoinColumn(name = "driver_offer_id"))
    @Column(name = "seat_label")
    @Builder.Default
    private List<String> availableSeats = new ArrayList<>();

    private String status; // "PENDING", "ACCEPTED", "REJECTED"

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
