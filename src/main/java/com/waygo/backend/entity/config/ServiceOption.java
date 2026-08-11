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

    /** FK to translation_keys; null until an admin adds a translation for {@link #name}. */
    private Long nameKeyId;

    private String iconKey; // To be used in Flutter to render an icon

    private String type; // "TOGGLE" or "INPUT"

    private boolean isActive;

    /** Form-binding only, not persisted: language code -> translated name, submitted by the admin form. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> nameTranslations = new java.util.HashMap<>();
}
