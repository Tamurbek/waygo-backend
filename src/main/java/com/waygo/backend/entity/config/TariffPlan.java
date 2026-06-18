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
    
    private BigDecimal price;
    
    private BigDecimal oldPrice;
    
    private String description;
    
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tariff_plan_features", joinColumns = @JoinColumn(name = "tariff_plan_id"))
    @Column(name = "feature")
    private List<String> features;
    
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
