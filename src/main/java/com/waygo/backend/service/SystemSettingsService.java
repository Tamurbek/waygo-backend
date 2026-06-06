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

    public static boolean isGlobalBillingEnabled() {
        return globalBillingEnabled;
    }

    public static boolean isVipTariffEnabled() {
        return vipTariffEnabled;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            globalBillingEnabled = getSettings().isBillingEnabled();
            vipTariffEnabled = getSettings().isVipTariffEnabled();
        } catch (Exception e) {
            globalBillingEnabled = false;
            vipTariffEnabled = true;
        }
    }

    public SystemSettings getSettings() {
        SystemSettings settings = repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(SystemSettings.builder()
                        .smsProvider("console")
                        .eskizEmail("test@test.com")
                        .eskizPassword("password")
                        .eskizFrom("4546")
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
