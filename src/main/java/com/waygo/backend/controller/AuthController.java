package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.dto.AuthenticationResponse;
import com.waygo.backend.dto.OtpRequest;
import com.waygo.backend.dto.OtpVerificationRequest;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final com.waygo.backend.service.OtpService otpService;
    private final com.waygo.backend.service.FileService fileService;

    @PostMapping("/request-otp")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> requestOtp(@RequestBody OtpRequest request) {
        System.out.println("DEBUG (request-otp): Incoming request body phone=" + request.getPhone());
        try {
            String code = otpService.sendVerificationCode(request.getPhone());
            // Only ever echo the code back for the fixed demo phones (used for App Store/Play
            // Store review logins). For real numbers the code must stay SMS-only, otherwise
            // anyone could read it straight from this response and skip verification entirely.
            String responseCode = otpService.isDemoPhone(request.getPhone()) ? code : "";
            return ResponseEntity.ok(ApiResponse.success(
                    java.util.Map.of("code", responseCode),
                    "Verification code sent to " + request.getPhone()));
        } catch (Exception e) {
            System.err.println("ERROR (request-otp): Failed for phone=" + request.getPhone() + ": " + e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error(
                    e.getMessage() != null ? e.getMessage() : "OTP yuborishda xatolik yuz berdi"));
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> verifyOtp(
            @RequestBody OtpVerificationRequest request
    ) {
        String phone = request.getPhone();
        String code = request.getCode();
        
        System.out.println("Received verify-otp request: phone=" + phone + ", code=" + code + ", role=" + request.getRole());
        
        if (!otpService.verifyCode(phone, code)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid or expired verification code"));
        }

        User user = userRepository.findByPhone(phone).orElse(null);

        if (user != null) {
            // User exists, handle role update automatically if necessary
            if (request.getRole() != null && user.getRole() != request.getRole()) {
                User.Role previousRole = user.getRole();
                user.setRole(request.getRole());
                // Same configurable free trial as a brand-new driver
                // registration below, for a passenger converting to a
                // driver for the first time. Gated on never having had a
                // tariff/expiry set before so switching roles back and
                // forth can't be used to keep re-granting free days.
                if (request.getRole() == User.Role.DRIVER
                        && previousRole != User.Role.DRIVER
                        && user.getTariffExpiryDate() == null
                        && user.getActiveTariff() == null) {
                    int trialDays = com.waygo.backend.service.SystemSettingsService.getFreeTrialDaysConfig();
                    user.setDriverBillingEnabled(true);
                    user.setTariffExpiryDate(java.time.LocalDateTime.now().plusDays(trialDays));
                }
                user = userRepository.save(user);
            }
            
            String jwtToken = jwtService.generateToken(user);
            return ResponseEntity.ok(ApiResponse.success(
                    AuthenticationResponse.builder().token(jwtToken).user(user).build(),
                    "Login successful"
            ));
        } else {
            // User does not exist
            if (request.getFullName() != null && request.getPassword() != null && request.getRole() != null) {
                // Registering with full data
                user = User.builder()
                        .phone(phone)
                        .fullName(request.getFullName())
                        .password(passwordEncoder.encode(request.getPassword()))
                        .role(request.getRole())
                        .build();
                // 14-day (or dynamic configured) free trial for new DRIVER accounts
                if (request.getRole() == User.Role.DRIVER) {
                    int trialDays = com.waygo.backend.service.SystemSettingsService.getFreeTrialDaysConfig();
                    user.setDriverBillingEnabled(true);
                    user.setTariffExpiryDate(java.time.LocalDateTime.now().plusDays(trialDays));
                }
                user = userRepository.save(user);

                String jwtToken = jwtService.generateToken(user);
                return ResponseEntity.ok(ApiResponse.success(
                        AuthenticationResponse.builder().token(jwtToken).user(user).build(),
                        "Registration successful"
                ));
            } else {
                // New user but no data provided yet - tell frontend to collect data
                return ResponseEntity.status(404).body(ApiResponse.error(null, "USER_NOT_FOUND"));
            }
        }
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> register(
            @RequestParam String phone,
            @RequestParam String fullName,
            @RequestParam String password,
            @RequestParam User.Role role
    ) {
        if (userRepository.findByPhone(phone).isPresent()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Phone already registered"));
        }

        User user = User.builder()
                .phone(phone)
                .fullName(fullName)
                .password(passwordEncoder.encode(password))
                .role(role)
                .build();
        
        user = userRepository.save(user);
        // Free trial for new DRIVER accounts
        if (role == User.Role.DRIVER) {
            int trialDays = com.waygo.backend.service.SystemSettingsService.getFreeTrialDaysConfig();
            user.setDriverBillingEnabled(true);
            user.setTariffExpiryDate(java.time.LocalDateTime.now().plusDays(trialDays));
            user = userRepository.save(user);
        }

        String jwtToken = jwtService.generateToken(user);
        
        return ResponseEntity.ok(ApiResponse.success(
            AuthenticationResponse.builder().token(jwtToken).user(user).build(), 
            "User registered successfully"
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> login(
            @RequestParam String phone,
            @RequestParam String password
    ) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(phone, password)
        );
        
        User user = userRepository.findByPhone(phone)
                .orElseThrow();
        
        String jwtToken = jwtService.generateToken(user);
        
        return ResponseEntity.ok(ApiResponse.success(
            AuthenticationResponse.builder().token(jwtToken).user(user).build(), 
            "Login successful"
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<User>> getCurrentUser(@RequestHeader("Authorization") String token) {
        String phone = jwtService.extractUsername(token.substring(7));
        User user = userRepository.findByPhone(phone).orElseThrow();
        if (user.getRole() == User.Role.DRIVER && (user.getDriverId() == null || user.getDriverId().isEmpty())) {
            user.setDriverId("WG" + (1000000 + new java.util.Random().nextInt(9000000)));
            user = userRepository.save(user);
        }
        return ResponseEntity.ok(ApiResponse.success(user, "Profile retrieved"));
    }

    @PostMapping(value = "/update-profile", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<User>> updateProfile(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String carNumber,
            @RequestParam(required = false) String carModel,
            @RequestParam(required = false) String carColor,
            @RequestParam(required = false) String carBrand,
            @RequestParam(required = false) org.springframework.web.multipart.MultipartFile image
    ) {
        try {
            String phone = jwtService.extractUsername(token.substring(7));
            User user = userRepository.findByPhone(phone).orElseThrow();
            
            if (fullName != null) user.setFullName(fullName);
            if (carNumber != null) {
                java.util.Optional<User> existingUserWithPlate = userRepository.findByCarNumber(carNumber);
                if (existingUserWithPlate.isPresent() && !existingUserWithPlate.get().getId().equals(user.getId())) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("Bu avtomobil raqami tizimda allaqachon ro'yxatdan o'tgan"));
                }
                user.setCarNumber(carNumber);
            }
            if (carModel != null) user.setCarModel(carModel);
            if (carColor != null) user.setCarColor(carColor);
            if (carBrand != null) user.setCarBrand(carBrand);
            
            if (image != null && !image.isEmpty()) {
                String fileName = fileService.saveFile(image);
                user.setImageUrl("https://backend.waygo.uz/uploads/" + fileName);
            }
            
            userRepository.save(user);
            return ResponseEntity.ok(ApiResponse.success(user, "Profile updated successfully"));
        } catch (java.io.IOException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to upload image: " + e.getMessage()));
        }
    }

    @PutMapping("/fcm-token")
    public ResponseEntity<ApiResponse<User>> updateFcmToken(
            @RequestHeader("Authorization") String token,
            @RequestParam String fcmToken
    ) {
        String phone = jwtService.extractUsername(token.substring(7));
        User user = userRepository.findByPhone(phone).orElseThrow();
        user.setFcmToken(fcmToken);
        user = userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success(user, "FCM token updated successfully"));
    }
}
