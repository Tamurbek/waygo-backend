package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "driver_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String carModel;
    private String carNumber;
    private String carColor;
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    private CarType carType;

    public enum CarType {
        ECONOMY, COMFORT, BUSINESS, DELIVERY
    }
}
