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
    @JoinColumn(name = "passenger_id", nullable = false)
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

    @Column(precision = 19, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

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
