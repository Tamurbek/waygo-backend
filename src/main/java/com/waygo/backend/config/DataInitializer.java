package com.waygo.backend.config;

import com.waygo.backend.entity.User;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

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

        // Generate driverId for existing drivers if they don't have one
        java.util.List<User> allDrivers = userRepository.findByRoleOrderByCreatedAtDesc(User.Role.DRIVER);
        for (User driver : allDrivers) {
            if (driver.getDriverId() == null || driver.getDriverId().isEmpty()) {
                driver.setDriverId("WG" + (1000000 + new java.util.Random().nextInt(9000000)));
                userRepository.save(driver);
                System.out.println("✅ Generated driver_id " + driver.getDriverId() + " for existing driver: " + driver.getFullName());
            }
        }
    }
}
