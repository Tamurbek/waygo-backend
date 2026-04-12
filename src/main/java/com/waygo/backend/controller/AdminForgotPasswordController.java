package com.waygo.backend.controller;

import com.waygo.backend.entity.PasswordResetToken;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PasswordResetTokenRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminForgotPasswordController {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/forgot-password")
    public String showForgotPasswordForm() {
        return "admin/forgot-password";
    }

    @PostMapping("/forgot-password")
    @Transactional
    public String processForgotPassword(@RequestParam("email") String email, HttpServletRequest request, Model model) {
        User user = userRepository.findByEmail(email).orElse(null);
        
        if (user == null || user.getRole() != User.Role.ADMIN) {
            model.addAttribute("error", "Ushbu email bilan admin topilmadi.");
            return "admin/forgot-password";
        }

        // Delete existing tokens for this user if any
        tokenRepository.deleteByUser(user);

        // Create new token
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiryDate(LocalDateTime.now().plusHours(24))
                .build();
        
        tokenRepository.save(resetToken);

        // Send Email
        String baseUrl = request.getScheme() + "://" + request.getServerName();
        if (request.getServerPort() != 80 && request.getServerPort() != 443) {
            baseUrl += ":" + request.getServerPort();
        }
        
        String resetLink = baseUrl + "/admin/reset-password?token=" + token;
        emailService.sendPasswordResetEmail(user.getEmail(), resetLink);

        model.addAttribute("success", "Parolni tiklash havolasi emailingizga yuborildi. Iltimos, pochtangizni tekshiring.");
        return "admin/forgot-password";
    }

    @GetMapping("/reset-password")
    public String showResetPasswordForm(@RequestParam("token") String token, Model model) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token).orElse(null);
        
        if (resetToken == null || resetToken.isExpired()) {
            model.addAttribute("error", "Havola noto'g'ri yoki muddati o'tgan.");
            return "admin/forgot-password";
        }

        model.addAttribute("token", token);
        return "admin/reset-password";
    }

    @PostMapping("/reset-password")
    @Transactional
    public String processResetPassword(@RequestParam("token") String token,
                                       @RequestParam("password") String password,
                                       @RequestParam("confirmPassword") String confirmPassword,
                                       Model model) {
        
        PasswordResetToken resetToken = tokenRepository.findByToken(token).orElse(null);
        
        if (resetToken == null || resetToken.isExpired()) {
            model.addAttribute("error", "Havola noto'g'ri yoki muddati o'tgan.");
            return "admin/forgot-password";
        }

        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Parollar mos kelmadi.");
            model.addAttribute("token", token);
            return "admin/reset-password";
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);

        // Delete token after successful reset
        tokenRepository.delete(resetToken);

        return "redirect:/admin/login?resetSuccess";
    }
}
