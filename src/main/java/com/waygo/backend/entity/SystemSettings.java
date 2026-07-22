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
    private String otpMessageTemplate = "WayGoUz ilovasini tasdiqlash kodi: %s";

    @Builder.Default
    private Boolean billingEnabled = false;

    @Builder.Default
    private Boolean vipTariffEnabled = true;

    @Builder.Default
    private Integer freeTrialDays = 14;

    private String telegramBotToken;
    private String telegramChatId;

    // App versiyalari — admin paneldan yangilanadi
    @Builder.Default
    private String userAppVersion = "1.0.0";

    @Builder.Default
    private String driverAppVersion = "1.0.0";

    public boolean isBillingEnabled() {
        return Boolean.TRUE.equals(this.billingEnabled);
    }

    public Boolean getVipTariffEnabled() {
        return this.vipTariffEnabled == null || this.vipTariffEnabled;
    }

    public boolean isVipTariffEnabled() {
        return getVipTariffEnabled();
    }

    public Integer getFreeTrialDays() {
        return this.freeTrialDays != null ? this.freeTrialDays : 14;
    }
}
