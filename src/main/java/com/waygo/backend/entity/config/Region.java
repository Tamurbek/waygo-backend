package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "regions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Region {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;

    /** Viloyat markazining geografik koordinatalari */
    private Double latitude;
    private Double longitude;
    
    private boolean isActive;
    
    @OneToMany(mappedBy = "region", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JsonIgnoreProperties("region")
    private List<District> districts;
}
