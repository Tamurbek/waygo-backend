package com.waygo.backend.service.impl;

import com.waygo.backend.entity.SystemSettings;
import com.waygo.backend.service.SmsService;
import com.waygo.backend.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service("dynamicSmsService")
@Primary
@RequiredArgsConstructor
public class DynamicSmsService implements SmsService {

    private final SystemSettingsService settingsService;
    private final ApplicationContext applicationContext;

    @Override
    public void sendSms(String phone, String message) {
        SystemSettings settings = settingsService.getSettings();
        String provider = settings.getSmsProvider();
        
        SmsService delegate;
        if ("eskiz".equalsIgnoreCase(provider)) {
            delegate = applicationContext.getBean("eskizSmsService", SmsService.class);
        } else {
            delegate = applicationContext.getBean("consoleSmsService", SmsService.class);
        }
        
        delegate.sendSms(phone, message);
    }
}
