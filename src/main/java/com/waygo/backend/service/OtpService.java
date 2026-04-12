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

    public String sendVerificationCode(String phone) {
        String code = generateOtp();
        otpStorage.put(phone, code);
        
        // Eskiz TEST rejimida bo'lgani uchun vaqtincha faqat shu matnni yuboramiz:
        String message = "Bu Eskiz dan test";
        smsService.sendSms(phone, message);
        
        // Asl matn (Moderatsiyadan o'tgach shuni ishlatasiz):
        // String message = "WayGO mobil ilovasiga kirish uchun tasdiqlash kodingiz: " + code;
        
        log.info("OTP code generated for {}: {}", phone, code);
        return code;
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
        return String.format("%04d", new Random().nextInt(10000));
    }
}
