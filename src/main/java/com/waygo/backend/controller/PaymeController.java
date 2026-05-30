package com.waygo.backend.controller;

import com.waygo.backend.dto.PaymeRequest;
import com.waygo.backend.dto.PaymeResponse;
import com.waygo.backend.service.PaymeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/payme")
@RequiredArgsConstructor
@Tag(name = "Payme Controller", description = "Webhook endpoints for Payme Merchant API billing integration")
public class PaymeController {

    private final PaymeService paymeService;

    @PostMapping
    @Operation(summary = "Handle JSON-RPC requests from Payme webhook")
    public ResponseEntity<PaymeResponse> handle(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                @RequestBody PaymeRequest request) {
        
        if (!paymeService.authorize(authHeader)) {
            PaymeResponse errorResponse = PaymeResponse.builder()
                    .error(PaymeResponse.PaymeError.builder()
                            .code(-32504)
                            .message(Map.of(
                                    "ru", "Недостаточно привилегий",
                                    "uz", "Ruxsat yetarli emas",
                                    "en", "Insufficient privilege"
                            ))
                            .build())
                    .id(request != null ? request.getId() : null)
                    .build();
            return ResponseEntity.status(401).body(errorResponse);
        }

        PaymeResponse response = paymeService.process(request);
        return ResponseEntity.ok(response);
    }
}
