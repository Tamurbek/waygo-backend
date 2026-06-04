package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "service_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceOption {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name; // e.g. "Konditsioner", "Bagaj", "Bolalar o'rindig'i", "Tirkama"
    
    private String iconKey; // To be used in Flutter to render an icon
    
    private String type; // "TOGGLE" or "INPUT"
    
    private boolean isActive;
}
