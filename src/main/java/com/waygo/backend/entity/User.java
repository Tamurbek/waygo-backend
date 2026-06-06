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

    @Column(name = "driver_id", unique = true)
    private String driverId;

    @Builder.Default
    @Column(precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Builder.Default
    private Double rating = 5.0;

    @Builder.Default
    private Integer tripsCount = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private Boolean driverBillingEnabled = false;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "active_tariff_id")
    private TariffPlan activeTariff;

    private LocalDateTime tariffExpiryDate;

    @Transient
    public boolean isBillingEnabled() {
        boolean billingActive = com.waygo.backend.service.SystemSettingsService.isGlobalBillingEnabled() 
                || Boolean.TRUE.equals(this.driverBillingEnabled);
        if (billingActive) {
            return this.balance == null || this.balance.compareTo(java.math.BigDecimal.ZERO) <= 0;
        }
        return false;
    }

    public boolean isDriverBillingEnabled() {
        return Boolean.TRUE.equals(this.driverBillingEnabled);
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
    public void generateDriverId() {
        if (this.role == Role.DRIVER && (this.driverId == null || this.driverId.isEmpty())) {
            this.driverId = "WG" + (1000000 + new java.util.Random().nextInt(9000000));
        }
    }
}
