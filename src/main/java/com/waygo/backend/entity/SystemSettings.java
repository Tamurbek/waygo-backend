package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String smsProvider; // 'console' or 'eskiz'
    
    private String eskizEmail;
    private String eskizPassword;
    private String eskizFrom;
    
    @Builder.Default
    private String otpMessageTemplate = "WayGO tasdiqlash kodi: %s";

    @Builder.Default
    private Boolean billingEnabled = false;

    public boolean isBillingEnabled() {
        return Boolean.TRUE.equals(this.billingEnabled);
    }
}
