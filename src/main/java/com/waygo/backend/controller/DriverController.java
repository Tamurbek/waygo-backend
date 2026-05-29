package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.DriverProfile;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.DriverProfileRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverProfileRepository driverProfileRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;

    @PostMapping("/register-vehicle")
    public ResponseEntity<ApiResponse<DriverProfile>> registerVehicle(
            @RequestParam String carModel,
            @RequestParam String carNumber,
            @RequestParam String carColor,
            @RequestParam(required = false) String licenseNumber,
            @RequestParam(required = false) DriverProfile.CarType carType
    ) {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }

        java.util.Optional<User> existingUserWithPlate = userRepository.findByCarNumber(carNumber);
        if (existingUserWithPlate.isPresent() && !existingUserWithPlate.get().getId().equals(user.getId())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Bu avtomobil raqami tizimda allaqachon ro'yxatdan o'tgan"));
        }

        DriverProfile profile = driverProfileRepository.findByUser(user)
                .orElse(DriverProfile.builder().user(user).build());

        profile.setCarModel(carModel);
        profile.setCarNumber(carNumber);
        profile.setCarColor(carColor);
        if (licenseNumber != null) profile.setLicenseNumber(licenseNumber);
        if (carType != null) profile.setCarType(carType);

        DriverProfile saved = driverProfileRepository.save(profile);
        
        // Sync to User entity for easy access in Orders
        user.setCarModel(carModel);
        user.setCarNumber(carNumber);

        // Update user role to DRIVER if it was PASSENGER
        if (user.getRole() == User.Role.PASSENGER) {
            user.setRole(User.Role.DRIVER);
        }
        userRepository.save(user);

        return ResponseEntity.ok(ApiResponse.success(saved, "Avtomobil muvaffaqiyatli ro'yxatdan o'tkazildi. Endi siz haydovchisiz!"));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<DriverProfile>> getProfile() {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }

        return driverProfileRepository.findByUser(user)
                .map(profile -> ResponseEntity.ok(ApiResponse.success(profile, "Profile retrieved")))
                .orElse(ResponseEntity.notFound().build());
    }
}
