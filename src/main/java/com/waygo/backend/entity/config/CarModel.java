package com.waygo.backend.entity.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "car_models")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarModel {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "brand_id")
    @JsonIgnoreProperties("models")
    private CarBrand brand;
    
    private boolean isActive;
}
