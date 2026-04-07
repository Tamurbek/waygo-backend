package com.waygo.backend.service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class OtpService {

    private final SmsService smsService;
    private final Map<String, String> otpStorage = new ConcurrentHashMap<>();

    public void sendVerificationCode(String phone) {
        String code = generateOtp();
        otpStorage.put(phone, code);
        
        String message = "WayGO: Tasdiqlash kodingiz: " + code;
        smsService.sendSms(phone, message);
        
        log.info("OTP code generated for {}: {}", phone, code);
    }

    public boolean verifyCode(String phone, String code) {
        String storedCode = otpStorage.get(phone);
        if (storedCode != null && storedCode.equals(code)) {
            otpStorage.remove(phone);
            return true;
        }
        return false;
    }

    private String generateOtp() {
        return String.format("%06d", new Random().nextInt(1000000));
    }
}
