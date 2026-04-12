package com.waygo.backend.service.impl;

import com.waygo.backend.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "waygo.mail.provider", havingValue = "smtp")
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Async
    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        try {
            Context context = new Context();
            context.setVariable("resetLink", resetLink);
            String process = templateEngine.process("emails/password-reset", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");
            helper.setText(process, true);
            helper.setTo(to);
            helper.setSubject("WayGO - Parolni tiklash");
            helper.setFrom("WayGO Support <" + fromEmail + ">");

            mailSender.send(mimeMessage);
            log.info("Password reset email sent to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send password reset email", e);
        }
    }
}
