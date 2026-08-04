package com.waygo.backend.entity.translation;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "languages", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Language {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. "uz", "ru", "en", "uz_cyrl" - free-form, admin-defined */
    private String code;

    private String name;

    private String flagEmoji;

    /** code of another Language to fall back to when a key has no value in this language */
    private String baseLanguageCode;

    private boolean active;

    private boolean isDefault;

    private Integer sortOrder;
}
