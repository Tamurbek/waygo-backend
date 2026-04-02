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
        if (userRepository.count() == 0) {
            String defaultPassword = passwordEncoder.encode("password123");
            
            // Test Passengers
            userRepository.save(User.builder()
                    .phone("+998901234567")
                    .fullName("Temur Yo'ldoshev")
                    .password(defaultPassword)
                    .role(User.Role.PASSENGER)
                    .balance(new BigDecimal("100000"))
                    .build());

            userRepository.save(User.builder()
                    .phone("+998901112233")
                    .fullName("Yo'lovchi Ali")
                    .role(User.Role.PASSENGER)
                    .balance(new BigDecimal("50000"))
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
