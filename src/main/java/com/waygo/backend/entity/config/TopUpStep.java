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

    /** FK to translation_keys; null until an admin adds a translation for {@link #title}/{@link #description}. */
    private Long titleKeyId;
    private Long descriptionKeyId;

    /** Form-binding only, not persisted: language code -> translated text, submitted by the admin form. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> titleTranslations = new java.util.HashMap<>();

    @Transient
    @Builder.Default
    private java.util.Map<String, String> descriptionTranslations = new java.util.HashMap<>();
}
