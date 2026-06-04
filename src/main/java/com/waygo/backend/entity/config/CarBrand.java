package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "car_brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarBrand {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    private boolean isActive;
    
    @OneToMany(mappedBy = "brand", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnore
    private List<CarModel> models;
}
