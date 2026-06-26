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

    private static final java.util.Set<String> DEMO_PHONES = java.util.Set.of(
            "+998900000001", "+998900000002", "+998999999999"
    );
    private static final String DEMO_OTP = "1111";
    private static final String FALLBACK_TEMPLATE = "WayGo tasdiqlash kodi: %s";

    public String sendVerificationCode(String phone) {
        // Demo/Test bypass logic
        if (DEMO_PHONES.contains(phone)) {
            log.info("Demo account OTP request for {}. SMS skipped. Code: {}", phone, DEMO_OTP);
            return DEMO_OTP;
        }

        String code = generateOtp();
        
        try {
            redisTemplate.opsForValue().set(
                    OTP_KEY_PREFIX + phone,
                    code,
                    java.time.Duration.ofMinutes(OTP_EXPIRATION_MINUTES)
            );
            log.info("OTP code saved to Redis for {}: {}", phone, code);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to save OTP to Redis for {}: {}", phone, e.getMessage(), e);
            throw new RuntimeException("OTP saqlashda xatolik yuz berdi. Iltimos qayta urinib ko'ring.", e);
        }

        try {
            // System settings dan shablonni olamiz
            String template = settingsService.getSettings().getOtpMessageTemplate();
            if (template == null || template.isBlank()) {
                template = FALLBACK_TEMPLATE;
                log.warn("OTP message template is null/blank, using fallback template.");
            }
            String message = String.format(template, code);
            smsService.sendSms(phone, message);
            log.info("SMS dispatch initiated for {}", phone);
        } catch (Exception e) {
            // SMS yuborishda xatolik bo'lsa ham OTP Redis'da saqlanib qoladi
            // @Async bo'lgani uchun bu blok odatda chaqirilmaydi, lekin sinxron xatolar uchun
            log.error("Failed to initiate SMS for {}: {}. OTP is still valid in Redis.", phone, e.getMessage(), e);
        }

        return code;
    }

    public boolean verifyCode(String phone, String code) {
        // Demo/Test bypass logic
        if (DEMO_PHONES.contains(phone) && DEMO_OTP.equals(code)) {
            log.info("Demo account OTP verified successfully for {}", phone);
            return true;
        }

        String key = OTP_KEY_PREFIX + phone;
        
        try {
            String storedCode = redisTemplate.opsForValue().get(key);
            log.info("Verifying code for {}. Input: {}, Stored: {}", phone, code,
                    storedCode == null ? "NULL (not found or expired)" : "[PRESENT]");

            if (storedCode != null && storedCode.equals(code)) {
                redisTemplate.delete(key);
                log.info("Code verified successfully for {}", phone);
                return true;
            }
            log.warn("Code verification failed for {}. Reason: {}", phone,
                    storedCode == null ? "OTP not found in Redis (expired or not sent)" : "Wrong code entered");
            return false;
        } catch (Exception e) {
            log.error("CRITICAL: Redis error during OTP verification for {}: {}", phone, e.getMessage(), e);
            return false;
        }
    }

    private String generateOtp() {
        return String.format("%04d", new Random().nextInt(10000));
    }
}
