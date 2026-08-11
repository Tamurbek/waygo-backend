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

    /** FK to translation_keys; null until an admin adds a translation for {@link #name}. */
    private Long nameKeyId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "brand_id")
    @JsonIgnoreProperties("models")
    private CarBrand brand;

    private boolean isActive;

    /** Form-binding only, not persisted: language code -> translated name, submitted by the admin form. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> nameTranslations = new java.util.HashMap<>();
}
