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

    /** FK to translation_keys; null until an admin adds a translation for {@link #name}. */
    private Long nameKeyId;

    @OneToMany(mappedBy = "brand", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<CarModel> models;

    /** Form-binding only, not persisted: language code -> translated name, submitted by the admin form. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> nameTranslations = new java.util.HashMap<>();
}
