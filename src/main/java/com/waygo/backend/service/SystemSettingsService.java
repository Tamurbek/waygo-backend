package com.waygo.backend.service;

import com.waygo.backend.entity.SystemSettings;
import com.waygo.backend.repository.SystemSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingsRepository repository;
    private static volatile boolean globalBillingEnabled = false;
    private static volatile boolean vipTariffEnabled = true;

    @org.springframework.beans.factory.annotation.Value("${waygo.sms.provider:eskiz}")
    private String defaultSmsProvider;

    @org.springframework.beans.factory.annotation.Value("${waygo.eskiz.email:farrux.xolmatov19890221@gmail.com}")
    private String defaultEskizEmail;

    @org.springframework.beans.factory.annotation.Value("${waygo.eskiz.password:KPFEnVlNoYwlwVkzSJvoLCCFbUhL33wz3fXasYIm}")
    private String defaultEskizPassword;

    @org.springframework.beans.factory.annotation.Value("${waygo.eskiz.from:4546}")
    private String defaultEskizFrom;

    public static boolean isGlobalBillingEnabled() {
        return globalBillingEnabled;
    }

    public static boolean isVipTariffEnabled() {
        return vipTariffEnabled;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            SystemSettings settings = getSettings();
            boolean needsSave = false;
            if ("test@test.com".equals(settings.getEskizEmail()) || "password".equals(settings.getEskizPassword()) || "temuryoldoshev10@gmail.com".equals(settings.getEskizEmail())) {
                settings.setSmsProvider(defaultSmsProvider);
                settings.setEskizEmail(defaultEskizEmail);
                settings.setEskizPassword(defaultEskizPassword);
                settings.setEskizFrom(defaultEskizFrom);
                needsSave = true;
            }
            if (settings.getOtpMessageTemplate() == null || 
                "WayGO tasdiqlash kodi: %s".equals(settings.getOtpMessageTemplate())) {
                settings.setOtpMessageTemplate("WayGoUz ilovasini tasdiqlash kodi: %s");
                needsSave = true;
            }
            if (needsSave) {
                repository.save(settings);
            }
            globalBillingEnabled = settings.isBillingEnabled();
            vipTariffEnabled = settings.isVipTariffEnabled();
        } catch (Exception e) {
            globalBillingEnabled = false;
            vipTariffEnabled = true;
        }
    }

    public SystemSettings getSettings() {
        SystemSettings settings = repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(SystemSettings.builder()
                        .smsProvider(defaultSmsProvider)
                        .eskizEmail(defaultEskizEmail)
                        .eskizPassword(defaultEskizPassword)
                        .eskizFrom(defaultEskizFrom)
                        .build()));
        globalBillingEnabled = settings.isBillingEnabled();
        vipTariffEnabled = settings.isVipTariffEnabled();
        return settings;
    }

    @Transactional
    public SystemSettings updateSettings(SystemSettings newSettings) {
        SystemSettings existing = getSettings();
        existing.setSmsProvider(newSettings.getSmsProvider());
        existing.setEskizEmail(newSettings.getEskizEmail());
        existing.setEskizPassword(newSettings.getEskizPassword());
        existing.setEskizFrom(newSettings.getEskizFrom());
        existing.setOtpMessageTemplate(newSettings.getOtpMessageTemplate());
        existing.setBillingEnabled(newSettings.isBillingEnabled());
        existing.setVipTariffEnabled(newSettings.isVipTariffEnabled());
        SystemSettings saved = repository.save(existing);
        globalBillingEnabled = saved.isBillingEnabled();
        vipTariffEnabled = saved.isVipTariffEnabled();
        return saved;
    }
}
