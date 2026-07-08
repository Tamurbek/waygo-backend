package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.waygo.backend.entity.config.TariffPlan;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "authorities", "password"})
public class User implements UserDetails {

    private static final long serialVersionUID = -1623425564715250968L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String phone;

    @Column(unique = true)
    private String email;

    private String fullName;

    private String imageUrl;

    private String password;

    @Enumerated(EnumType.STRING)
    private Role role;

    private String carNumber;
    private String carModel;
    private String carColor;
    private String carBrand;

    @Column(name = "driver_id", unique = true)
    private String driverId;

    @JsonIgnore
    @Builder.Default
    @Column(precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Builder.Default
    private Double rating = 5.0;

    @Builder.Default
    private Integer tripsCount = 0;

    @Column(unique = true)
    private String referralCode;

    private Long referredById;

    @Builder.Default
    private Integer pointsBalance = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private Boolean driverBillingEnabled = false;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "active_tariff_id")
    private TariffPlan activeTariff;

    private LocalDateTime tariffExpiryDate;

    private Long frozenTariffDuration;

    @Transient
    public boolean isBillingEnabled() {
        if (this.role == Role.DRIVER && !isDriverBillingEnabled()) {
            return false;
        }
        boolean billingActive = com.waygo.backend.service.SystemSettingsService.isGlobalBillingEnabled() 
                || isDriverBillingEnabled();
        if (billingActive) {
            if (this.tariffExpiryDate != null && this.tariffExpiryDate.isAfter(LocalDateTime.now())) {
                return false;
            }
            return this.balance == null || this.balance.compareTo(java.math.BigDecimal.ZERO) <= 0;
        }
        return false;
    }

    public boolean isDriverBillingEnabled() {
        return Boolean.TRUE.equals(this.driverBillingEnabled);
    }

    @Transient
    @JsonProperty("vipTariffEnabled")
    public boolean isVipTariffEnabled() {
        return com.waygo.backend.service.SystemSettingsService.isVipTariffEnabled();
    }

    @JsonProperty("balance")
    public BigDecimal getBalanceForJson() {
        if (this.role == Role.DRIVER && !isDriverBillingEnabled()) {
            return null;
        }
        return this.balance;
    }

    public void freezeTariff() {
        if (this.tariffExpiryDate != null && this.tariffExpiryDate.isAfter(LocalDateTime.now())) {
            this.frozenTariffDuration = java.time.Duration.between(LocalDateTime.now(), this.tariffExpiryDate).toSeconds();
        } else {
            this.frozenTariffDuration = null;
        }
        this.tariffExpiryDate = null;
    }

    public void unfreezeTariff() {
        if (this.frozenTariffDuration != null && this.frozenTariffDuration > 0) {
            this.tariffExpiryDate = LocalDateTime.now().plusSeconds(this.frozenTariffDuration);
        }
        this.frozenTariffDuration = null;
    }

    public String getFrozenTariffDurationFormatted() {
        if (frozenTariffDuration == null || frozenTariffDuration <= 0) {
            return null;
        }
        long days = frozenTariffDuration / 86400;
        long hours = (frozenTariffDuration % 86400) / 3600;
        long minutes = (frozenTariffDuration % 3600) / 60;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" kun ");
        }
        if (hours > 0) {
            sb.append(hours).append(" soat ");
        }
        if (minutes > 0) {
            sb.append(minutes).append(" daqiqa");
        }
        if (sb.length() == 0) {
            sb.append(frozenTariffDuration).append(" soniya");
        }
        return sb.toString().trim();
    }

    public String getInitials() {
        if (fullName == null || fullName.trim().isEmpty()) {
            if (phone != null && phone.length() >= 2) {
                return phone.substring(phone.length() - 2);
            }
            return "WG";
        }
        String cleaned = fullName.trim();
        String[] parts = cleaned.split("\\s+");
        if (parts.length >= 2 && parts[0].length() > 0 && parts[1].length() > 0) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        }
        if (cleaned.length() >= 2) {
            return cleaned.substring(0, 2).toUpperCase();
        }
        return cleaned.toUpperCase();
    }

    public enum Role {
        PASSENGER, DRIVER, ADMIN
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return phone != null ? phone : email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @PrePersist
    @PreUpdate
    public void generateDriverIdAndReferral() {
        if (this.role == Role.DRIVER && (this.driverId == null || this.driverId.isEmpty())) {
            this.driverId = "WG" + (1000000 + new java.util.Random().nextInt(9000000));
        }
        if (this.referralCode == null || this.referralCode.isEmpty()) {
            this.referralCode = "WG_" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }
}
