package com.waygo.backend.entity.translation;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "translation_keys", uniqueConstraints = @UniqueConstraint(columnNames = {"key_code", "app_target"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranslationKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_code")
    private String keyCode;

    @Enumerated(EnumType.STRING)
    private AppTarget appTarget;

    private String description;

    private String screen;

    public enum AppTarget {
        USER, DRIVER, SHARED
    }
}
