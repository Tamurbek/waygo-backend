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

    /** FK to translation_keys; null until an admin adds a translation for {@link #name}. */
    private Long nameKeyId;

    /** Tuman markazining geografik koordinatalari */
    private Double latitude;
    private Double longitude;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "region_id")
    @JsonIgnoreProperties("districts")
    private Region region;

    private boolean isActive;

    /** Form-binding only, not persisted: language code -> translated name, submitted by the admin form. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> nameTranslations = new java.util.HashMap<>();
}
