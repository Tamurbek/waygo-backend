package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
    
    private boolean isActive;
    
    @OneToMany(mappedBy = "region", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnore
    private List<District> districts;
}
