package com.waygo.backend.service.impl;

import com.waygo.backend.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConsoleSmsServiceImpl implements SmsService {

    @Override
    public void sendSms(String phone, String message) {
        log.info("Sending SMS to {}: {}", phone, message);
        // Simulation of SMS sending success for UI/Testing
        System.out.println("========================================");
        System.out.println("SMS SENT TO: " + phone);
        System.out.println("MESSAGE: " + message);
        System.out.println("========================================");
    }
}
