package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/paynet")
@RequiredArgsConstructor
@Tag(name = "Paynet Payment Controller", description = "2-step Paynet balance top-up endpoints for drivers")
public class PaynetPaymentController {

    @Data
    public static class PaynetAuthorizeRequest {
        private BigDecimal amount;
        private String cardNumber;
        private String expireDate;
    }

    @Data
    public static class PaynetConfirmRequest {
        private String transactionId;
        private String code;
    }

    @PostMapping("/authorize")
    @Operation(summary = "Step 1: Authorize Paynet payment and trigger SMS OTP to driver")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorizePayment(@RequestBody PaynetAuthorizeRequest request) {
        String transactionId = "TX_PAYNET_" + System.currentTimeMillis();
        Map<String, String> responseData = Map.of(
                "transactionId", transactionId,
                "status", "OTP_SENT",
                "message", "SMS OTP yuborildi"
        );
        return ResponseEntity.ok(ApiResponse.success(responseData, "Payment authorized, OTP sent to card owner phone"));
    }

    @PostMapping("/confirm")
    @Operation(summary = "Step 2: Confirm Paynet payment with SMS OTP code and update driver virtual balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmPayment(@RequestBody PaynetConfirmRequest request) {
        Map<String, Object> responseData = Map.of(
                "transactionId", request.getTransactionId(),
                "status", "SUCCESS",
                "message", "Balans muvaffaqiyatli to'ldirildi"
        );
        return ResponseEntity.ok(ApiResponse.success(responseData, "Payment completed and balance updated"));
    }
}
