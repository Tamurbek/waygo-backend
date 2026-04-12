package com.waygo.backend.service.impl;

import com.waygo.backend.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Primary
@ConditionalOnProperty(name = "waygo.mail.provider", havingValue = "console", matchIfMissing = true)
@Slf4j
public class ConsoleEmailServiceImpl implements EmailService {

    @Async
    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        log.info("----------------------------------------------------------------");
        log.info("EMAIL SENT TO: {}", to);
        log.info("SUBJECT: WayGO - Parolni tiklash");
        log.info("CONTENT: Assalomu alaykum! Parolni yangilash uchun quyidagi havolani bosing:");
        log.info("RESET LINK: {}", resetLink);
        log.info("----------------------------------------------------------------");
    }
}
