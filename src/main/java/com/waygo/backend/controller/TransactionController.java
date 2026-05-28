package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.Transaction;
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

    @PostMapping("/pay")
    @Operation(summary = "Process a payment from passenger to driver")
    public ResponseEntity<ApiResponse<Transaction>> pay(
            @RequestParam Long senderId,
            @RequestParam Long receiverId,
            @RequestParam BigDecimal amount) {
        
        Transaction transaction = transactionService.processPayment(senderId, receiverId, amount);
        return ResponseEntity.ok(ApiResponse.success(transaction, "Payment successful"));
    }

    @PostMapping("/top-up")
    @Operation(summary = "Top up a user's balance")
    public ResponseEntity<ApiResponse<com.waygo.backend.entity.User>> topUp(
            @RequestParam Long userId,
            @RequestParam BigDecimal amount) {
        com.waygo.backend.entity.User user = transactionService.topUp(userId, amount);
        return ResponseEntity.ok(ApiResponse.success(user, "Top up successful"));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get transaction history for a specific user")
    public ResponseEntity<ApiResponse<List<Transaction>>> getUserHistory(@PathVariable Long userId) {
        List<Transaction> history = transactionService.getUserTransactions(userId);
        return ResponseEntity.ok(ApiResponse.success(history, "History retrieved successfully"));
    }
}
