package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "car_colors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarColor {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    private String hexCode;
    
    private boolean isActive;
}
