package com.waygo.backend.entity.config;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "tariff_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TariffPlan {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String duration; // e.g. "1 kunlik", "1 oy"
    
    private Integer durationDays; // e.g. 1, 7, 30

    public Integer getDurationDays() {
        if (this.durationDays != null && this.durationDays > 0) {
            return this.durationDays;
        }
        if (this.duration != null) {
            String dStr = this.duration.toLowerCase();
            if (dStr.contains("kun")) {
                try { return Integer.parseInt(dStr.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            } else if (dStr.contains("oy")) {
                try { return Integer.parseInt(dStr.replaceAll("[^0-9]", "")) * 30; } catch (Exception ignored) {}
            } else if (dStr.contains("yil")) {
                try { return Integer.parseInt(dStr.replaceAll("[^0-9]", "")) * 365; } catch (Exception ignored) {}
            }
        }
        return 1;
    }
    
    private BigDecimal price;

    private BigDecimal oldPrice;

    private String description;

    /** FK to translation_keys; null until an admin adds a translation for {@link #duration}/{@link #description}. */
    private Long durationKeyId;
    private Long descriptionKeyId;

    /**
     * No @OrderColumn here on purpose: this table already has production rows, and
     * ddl-auto=update does not reliably ALTER an existing @ElementCollection table to add
     * an order column (confirmed in prod: "column list_index does not exist" 500s right
     * after deploy). Alignment with {@link #featureKeyIds} therefore relies on Postgres's
     * de-facto insertion-order return for this simple, never-ORDER-BY'd collection table —
     * good enough in practice, not a hard guarantee.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tariff_plan_features", joinColumns = @JoinColumn(name = "tariff_plan_id"))
    @Column(name = "feature")
    private List<String> features;

    /**
     * Parallel to {@link #features} by index: FK to translation_keys for each feature row's
     * translations. Kept as a separate collection (not embedded in features) because
     * ElementCollection of Strings can't carry a second value per row.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tariff_plan_feature_keys", joinColumns = @JoinColumn(name = "tariff_plan_id"))
    @Column(name = "key_id")
    @OrderColumn(name = "list_index")
    private List<Long> featureKeyIds;

    /** Form-binding only, not persisted: one language->text map per feature row, aligned by index with features. */
    @Transient
    @Builder.Default
    private List<java.util.Map<String, String>> featureTranslations = new java.util.ArrayList<>();

    /** Form-binding only, not persisted: language code -> translated duration text. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> durationTranslations = new java.util.HashMap<>();

    /** Form-binding only, not persisted: language code -> translated description text. */
    @Transient
    @Builder.Default
    private java.util.Map<String, String> descriptionTranslations = new java.util.HashMap<>();

    private boolean isPopular;
    
    private boolean isActive;
    
    private Boolean isVip;

    public boolean isVip() {
        return Boolean.TRUE.equals(this.isVip);
    }

    public boolean getVip() {
        return isVip();
    }

    public void setVip(Boolean vip) {
        this.isVip = vip;
    }
}
