package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.User;
import com.waygo.backend.service.MulticardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/multicard")
@RequiredArgsConstructor
@Tag(name = "Multicard Payment Controller", description = "Endpoints for Multicard acquiring integration")
@Slf4j
public class MulticardPaymentController {

    private final MulticardService multicardService;

    @Data
    public static class CreateInvoiceRequest {
        private BigDecimal amount;
    }

    @PostMapping("/create-invoice")
    @Operation(summary = "Create acquiring invoice in Multicard and get checkout URL")
    public ResponseEntity<ApiResponse<Map<String, String>>> createInvoice(
            @RequestBody CreateInvoiceRequest request,
            @AuthenticationPrincipal User driver
    ) {
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Аутентификациядан ўтилмаган"));
        }

        try {
            String checkoutUrl = multicardService.createInvoice(driver, request.getAmount());
            return ResponseEntity.ok(ApiResponse.success(Map.of("checkoutUrl", checkoutUrl), "Инвойс муваффақиятли яратилди"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Хатолик юз берди: " + e.getMessage()));
        }
    }

    @PostMapping("/callback")
    @Operation(summary = "Public webhook endpoint for Multicard payment results notification")
    public ResponseEntity<?> handleCallback(@RequestBody Map<String, Object> payload) {
        try {
            multicardService.processCallback(payload);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (SecurityException e) {
            log.error("Multicard callback signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            log.error("Multicard callback validation error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Multicard callback internal error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
