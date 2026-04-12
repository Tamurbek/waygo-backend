package com.waygo.backend.service.impl;

import com.waygo.backend.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "waygo.sms.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsServiceImpl implements SmsService {

    @Override
    public void sendSms(String phone, String message) {
        log.info("Sending SMS to {}: {}", phone, message);
        // Simulation of SMS sending success for UI/Testing
        System.err.println("\n\n########################################");
        System.err.println("!!! SMS GATEWAY SIMULATION !!!");
        System.err.println("TO: " + phone);
        System.err.println("BODY: " + message);
        System.err.println("########################################\n\n");
    }
}
