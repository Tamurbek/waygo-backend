package com.waygo.backend.entity.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "districts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class District {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "region_id")
    @JsonIgnoreProperties("districts")
    private Region region;
    
    private boolean isActive;
}
