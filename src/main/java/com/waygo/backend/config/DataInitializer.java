package com.waygo.backend.config;

import com.waygo.backend.entity.User;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;


import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute("ALTER TABLE orders ALTER COLUMN passenger_id DROP NOT NULL");
            System.out.println("✅ Fixed passenger_id NOT NULL constraint on orders table");
        } catch (Exception e) {
            System.out.println("⚠️ Could not alter orders table passenger_id constraint: " + e.getMessage());
        }

        String defaultPassword = passwordEncoder.encode("password123");

        // System Admin (doim tekshirib kiritiladi)
        if (userRepository.findByEmail("admin@waygo.uz").isEmpty()) {
            userRepository.save(User.builder()
                    .email("admin@waygo.uz")
                    .phone("+998000000000")
                    .fullName("System Admin")
                    .password(defaultPassword)
                    .role(User.Role.ADMIN)
                    .build());
            System.out.println("✅ System Admin yaratildi: admin@waygo.uz / password123");
        }
    }
}
