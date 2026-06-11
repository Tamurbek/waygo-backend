package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "top_up_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopUpStep {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Integer stepNumber;
    
    private String title;
    
    @Column(length = 2000)
    private String description;
    
    private String imageUrl; // optional path/url for step image/screenshot
}
