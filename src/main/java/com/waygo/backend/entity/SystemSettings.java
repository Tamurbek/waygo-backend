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

    private String telegramBotToken;
    private String telegramChatId;

    public boolean isBillingEnabled() {
        return Boolean.TRUE.equals(this.billingEnabled);
    }

    public Boolean getVipTariffEnabled() {
        return this.vipTariffEnabled == null || this.vipTariffEnabled;
    }

    public boolean isVipTariffEnabled() {
        return getVipTariffEnabled();
    }
}
