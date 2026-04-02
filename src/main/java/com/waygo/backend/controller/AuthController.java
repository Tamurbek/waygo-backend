package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.dto.AuthenticationResponse;
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
        
        userRepository.save(user);
        String jwtToken = jwtService.generateToken(user);
        
        return ResponseEntity.ok(ApiResponse.success(
            AuthenticationResponse.builder().token(jwtToken).build(), 
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
            AuthenticationResponse.builder().token(jwtToken).build(), 
            "Login successful"
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<User>> getCurrentUser(@RequestHeader("Authorization") String token) {
        String phone = jwtService.extractUsername(token.substring(7));
        User user = userRepository.findByPhone(phone).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(user, "Profile retrieved"));
    }
}
