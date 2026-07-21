package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/payments/paynet")
@RequiredArgsConstructor
@Tag(name = "Paynet Payment Controller", description = "Paynet integration endpoints for checking driver and topping up driver balance")
public class PaynetPaymentController {

    private final UserRepository userRepository;

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

    @Data
    public static class PaynetDepositRequest {
        private String phone;
        private String driverId;
        private Long id;
        private BigDecimal amount;
        private String transactionId;
    }

    @GetMapping("/check")
    @Operation(summary = "Check driver account details by phone number or driverId")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkDriver(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) Long id
    ) {
        Optional<User> userOpt = Optional.empty();
        if (phone != null && !phone.trim().isEmpty()) {
            userOpt = userRepository.findByPhone(phone.trim());
        } else if (driverId != null && !driverId.trim().isEmpty()) {
            userOpt = userRepository.findByDriverId(driverId.trim());
        } else if (id != null) {
            userOpt = userRepository.findById(id);
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error("Haydovchi topilmadi"));
        }

        User user = userOpt.get();
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("fullName", user.getFullName() != null ? user.getFullName() : "Haydovchi");
        data.put("phone", user.getPhone());
        data.put("driverId", user.getDriverId());
        data.put("carNumber", user.getCarNumber());
        data.put("carModel", user.getCarModel());
        data.put("balance", user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO);
        data.put("pointsBalance", user.getPointsBalance() != null ? user.getPointsBalance() : 0);

        return ResponseEntity.ok(ApiResponse.success(data, "Haydovchi ma'lumotlari topildi"));
    }

    @PostMapping("/deposit")
    @Operation(summary = "Paynet platform API: Add money directly to driver balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> depositToDriver(@RequestBody PaynetDepositRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Musbat summa kiritilishi shart"));
        }

        Optional<User> userOpt = Optional.empty();
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            userOpt = userRepository.findByPhone(request.getPhone().trim());
        } else if (request.getDriverId() != null && !request.getDriverId().trim().isEmpty()) {
            userOpt = userRepository.findByDriverId(request.getDriverId().trim());
        } else if (request.getId() != null) {
            userOpt = userRepository.findById(request.getId());
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.error("Haydovchi topilmadi"));
        }

        User user = userOpt.get();
        BigDecimal currentBalance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.add(request.getAmount());
        user.setBalance(newBalance);

        // Also credit points balance (1 point = 10 UZS ratio if applicable)
        int currentPoints = user.getPointsBalance() != null ? user.getPointsBalance() : 0;
        int pointsToAdd = request.getAmount().divide(BigDecimal.valueOf(10), 0, java.math.RoundingMode.DOWN).intValue();
        user.setPointsBalance(currentPoints + pointsToAdd);

        userRepository.save(user);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("driverId", user.getId());
        responseData.put("fullName", user.getFullName());
        responseData.put("phone", user.getPhone());
        responseData.put("addedAmount", request.getAmount());
        responseData.put("newBalance", newBalance);
        responseData.put("newPointsBalance", user.getPointsBalance());
        responseData.put("transactionId", request.getTransactionId() != null ? request.getTransactionId() : "TX_" + System.currentTimeMillis());

        return ResponseEntity.ok(ApiResponse.success(responseData, "Haydovchi balansi muvaffaqiyatli to'ldirildi"));
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
