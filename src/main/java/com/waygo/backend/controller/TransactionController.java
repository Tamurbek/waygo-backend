package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.Transaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.security.SecurityUtils;
import com.waygo.backend.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transaction Controller", description = "Endpoints for managing payments and money transfers")
public class TransactionController {

    private final TransactionService transactionService;
    private final SecurityUtils securityUtils;

    // /pay and /top-up are raw balance-mutating primitives with no legitimate
    // direct caller in either mobile app (real trip payments go through
    // OrderService.completeTrip, and tariff purchases are self-service via
    // TariffController using the caller's own id) — restrict both to admins,
    // matching how the equivalent manual-balance actions in AdminController
    // are gated behind /admin/** + hasRole("ADMIN").

    @PostMapping("/pay")
    @Operation(summary = "Process a payment from passenger to driver (admin only)")
    public ResponseEntity<ApiResponse<Transaction>> pay(
            @RequestParam Long senderId,
            @RequestParam Long receiverId,
            @RequestParam BigDecimal amount) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }
        if (currentUser.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).body(ApiResponse.error("Bu amal faqat administrator uchun ruxsat etilgan"));
        }

        Transaction transaction = transactionService.processPayment(senderId, receiverId, amount);
        return ResponseEntity.ok(ApiResponse.success(transaction, "Payment successful"));
    }

    @PostMapping("/top-up")
    @Operation(summary = "Top up a user's balance (admin only)")
    public ResponseEntity<ApiResponse<User>> topUp(
            @RequestParam Long userId,
            @RequestParam BigDecimal amount) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }
        if (currentUser.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).body(ApiResponse.error("Bu amal faqat administrator uchun ruxsat etilgan"));
        }

        User user = transactionService.topUp(userId, amount);
        return ResponseEntity.ok(ApiResponse.success(user, "Top up successful"));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get transaction history for a specific user")
    public ResponseEntity<ApiResponse<List<Transaction>>> getUserHistory(@PathVariable Long userId) {
        User currentUser = securityUtils.getCurrentUser();
        if (currentUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }
        if (!currentUser.getId().equals(userId) && currentUser.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).body(ApiResponse.error("Bu amalni bajarish uchun ruxsatingiz yo'q"));
        }

        List<Transaction> history = transactionService.getUserTransactions(userId);
        return ResponseEntity.ok(ApiResponse.success(history, "History retrieved successfully"));
    }
}
