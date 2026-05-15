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
    private final SystemSettingsService settingsService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private static final String OTP_KEY_PREFIX = "otp:";
    private static final long OTP_EXPIRATION_MINUTES = 2;

    public String sendVerificationCode(String phone) {
        String code = generateOtp();
        redisTemplate.opsForValue().set(
                OTP_KEY_PREFIX + phone, 
                code, 
                java.time.Duration.ofMinutes(OTP_EXPIRATION_MINUTES)
        );
        
        // System settings dan shablonni olamiz
        String template = settingsService.getSettings().getOtpMessageTemplate();
        String message = String.format(template, code);
        
        smsService.sendSms(phone, message);
        
        log.info("OTP code generated and saved to Redis for {}: {}", phone, code);
        return code;
    }

    public boolean verifyCode(String phone, String code) {
        String key = OTP_KEY_PREFIX + phone;
        String storedCode = redisTemplate.opsForValue().get(key);
        
        log.info("Verifying code for {}. Input: {}, Stored: {}", phone, code, storedCode);
        
        if (storedCode != null && storedCode.equals(code)) {
            redisTemplate.delete(key);
            log.info("Code verified successfully for {}", phone);
            return true;
        }
        log.warn("Code verification failed for {}. Stored code is {}", phone, storedCode == null ? "NULL (expired)" : storedCode);
        return false;
    }

    private String generateOtp() {
        return String.format("%04d", new Random().nextInt(10000));
    }
}
