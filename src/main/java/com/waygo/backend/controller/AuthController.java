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
    public ResponseEntity<ApiResponse<String>> requestOtp(@RequestBody OtpRequest request) {
        String code = otpService.sendVerificationCode(request.getPhone());
        return ResponseEntity.ok(ApiResponse.success(code, "Verification code sent to " + request.getPhone()));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> verifyOtp(
            @RequestBody OtpVerificationRequest request
    ) {
        String phone = request.getPhone();
        String code = request.getCode();
        
        if (!otpService.verifyCode(phone, code)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid or expired verification code"));
        }

        User user = userRepository.findByPhone(phone).orElse(null);

        if (request.isLogin()) {
            if (user == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Siz tizimda ro'yxatdan o'tmagansiz. Iltimos, ro'yxatdan o'ting."));
            }
        } else {
            // Register mode
            if (user != null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Bu raqam allaqachon ro'yxatdan o'tgan. Iltimos, kirish qismidan foydalaning."));
            }
            
            // New user registration
            if (request.getFullName() == null || request.getPassword() == null || request.getRole() == null) {
                 return ResponseEntity.badRequest().body(ApiResponse.error("User registration requires full name, password, and role"));
            }
            user = User.builder()
                    .phone(phone)
                    .fullName(request.getFullName())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(request.getRole())
                    .build();
            user = userRepository.save(user);
        }

        String jwtToken = jwtService.generateToken(user);
        return ResponseEntity.ok(ApiResponse.success(
                AuthenticationResponse.builder().token(jwtToken).user(user).build(),
                "Verification successful"
        ));
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
        return ResponseEntity.ok(ApiResponse.success(user, "Profile retrieved"));
    }

    @PostMapping(value = "/update-profile", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<User>> updateProfile(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) org.springframework.web.multipart.MultipartFile image
    ) {
        try {
            String phone = jwtService.extractUsername(token.substring(7));
            User user = userRepository.findByPhone(phone).orElseThrow();
            
            if (fullName != null) {
                user.setFullName(fullName);
            }
            
            if (image != null && !image.isEmpty()) {
                String fileName = fileService.saveFile(image);
                // In production, use your domain here. For local testing, we use localhost.
                user.setImageUrl("http://localhost:8080/uploads/" + fileName);
            }
            
            userRepository.save(user);
            return ResponseEntity.ok(ApiResponse.success(user, "Profile updated successfully"));
        } catch (java.io.IOException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to upload image: " + e.getMessage()));
        }
    }
}
