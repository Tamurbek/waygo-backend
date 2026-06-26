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
    private final com.waygo.backend.service.ReferralService referralService;

    @PostMapping("/request-otp")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> requestOtp(@RequestBody OtpRequest request) {
        String code = otpService.sendVerificationCode(request.getPhone());
        return ResponseEntity.ok(ApiResponse.success(
                java.util.Map.of("code", code), 
                "Verification code sent to " + request.getPhone()));
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
                user.setRole(request.getRole());
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
                user = userRepository.save(user);
                
                if (request.getReferralCode() != null && !request.getReferralCode().isEmpty()) {
                    referralService.processReferralCodeDuringRegistration(user, request.getReferralCode());
                }
                
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
            @RequestParam User.Role role,
            @RequestParam(required = false) String referralCode
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
        if (referralCode != null && !referralCode.isEmpty()) {
            referralService.processReferralCodeDuringRegistration(user, referralCode);
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
            
            if (image != null && !image.isEmpty()) {
                String fileName = fileService.saveFile(image);
                user.setImageUrl("https://waygo.uz/uploads/" + fileName);
            }
            
            userRepository.save(user);
            return ResponseEntity.ok(ApiResponse.success(user, "Profile updated successfully"));
        } catch (java.io.IOException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to upload image: " + e.getMessage()));
        }
    }
}
