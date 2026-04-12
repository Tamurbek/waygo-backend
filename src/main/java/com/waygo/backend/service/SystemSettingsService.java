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

    public SystemSettings getSettings() {
        return repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(SystemSettings.builder()
                        .smsProvider("console")
                        .eskizEmail("test@test.com")
                        .eskizPassword("password")
                        .eskizFrom("4546")
                        .build()));
    }

    @Transactional
    public SystemSettings updateSettings(SystemSettings newSettings) {
        SystemSettings existing = getSettings();
        existing.setSmsProvider(newSettings.getSmsProvider());
        existing.setEskizEmail(newSettings.getEskizEmail());
        existing.setEskizPassword(newSettings.getEskizPassword());
        existing.setEskizFrom(newSettings.getEskizFrom());
        existing.setOtpMessageTemplate(newSettings.getOtpMessageTemplate());
        return repository.save(existing);
    }
}
