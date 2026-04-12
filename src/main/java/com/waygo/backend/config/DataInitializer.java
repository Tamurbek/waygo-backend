package com.waygo.backend.config;

import com.waygo.backend.entity.User;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String defaultPassword = passwordEncoder.encode("password123");
        
        // System Admin (doim tekshirib kiritiladi)
        if (userRepository.findByEmail("admin@waygo.uz").isEmpty()) {
            userRepository.save(User.builder()
                    .email("admin@waygo.uz")
                    .fullName("System Admin")
                    .password(defaultPassword)
                    .role(User.Role.ADMIN)
                    .build());
        }

        if (userRepository.count() <= 1) { // 1 marta admin qo'shilgan bo'lsa
            // Test Passengers
            userRepository.save(User.builder()
                    .phone("+998901234567")
                    .fullName("Temur Yo'ldoshev")
                    .password(defaultPassword)
                    .role(User.Role.PASSENGER)
                    .balance(BigDecimal.ZERO)
                    .build());

            userRepository.save(User.builder()
                    .phone("+998901112233")
                    .fullName("Yo'lovchi Ali")
                    .role(User.Role.PASSENGER)
                    .balance(BigDecimal.ZERO)
                    .build());

            // Test Drivers
            userRepository.save(User.builder()
                    .phone("+998991234567")
                    .fullName("Haydovchi Valijon")
                    .role(User.Role.DRIVER)
                    .balance(new BigDecimal("0"))
                    .build());
            
            userRepository.save(User.builder()
                    .phone("+998997778899")
                    .fullName("Haydovchi Sardor")
                    .role(User.Role.DRIVER)
                    .balance(new BigDecimal("0"))
                    .build());

            System.out.println("✅ Test ma'lumotlari (Yo'lovchi va Haydovchilar) yaratildi!");
        }
    }
}
