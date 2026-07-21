package com.waygo.backend.controller;

import com.waygo.backend.entity.PaynetTransaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PaynetTransactionRepository;
import com.waygo.backend.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/payments/paynet/uws")
@RequiredArgsConstructor
@Tag(name = "Official Paynet UWS JSON-RPC 2.0 Controller", description = "Official Paynet Provider Protocol (GetInformation, PerformTransaction, CheckTransaction, CancelTransaction, GetStatement)")
public class PaynetUwsController {

    private final UserRepository userRepository;
    private final PaynetTransactionRepository paynetTransactionRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Data
    public static class JsonRpcRequest {
        private String jsonrpc;
        private String method;
        private Object id;
        private Map<String, Object> params;
    }

    @PostMapping
    @Operation(summary = "Official Paynet UWS JSON-RPC 2.0 Web Service Endpoint")
    public ResponseEntity<Map<String, Object>> handleRpc(@RequestBody JsonRpcRequest request) {
        String method = request.getMethod();
        Object id = request.getId();
        Map<String, Object> params = request.getParams() != null ? request.getParams() : Collections.emptyMap();

        if (method == null) {
            return ResponseEntity.ok(buildErrorResponse(id, 603, "Неправильный код команды"));
        }

        try {
            switch (method.trim()) {
                case "GetInformation":
                    return ResponseEntity.ok(handleGetInformation(id, params));
                case "PerformTransaction":
                    return ResponseEntity.ok(handlePerformTransaction(id, params));
                case "CheckTransaction":
                    return ResponseEntity.ok(handleCheckTransaction(id, params));
                case "CancelTransaction":
                    return ResponseEntity.ok(handleCancelTransaction(id, params));
                case "GetStatement":
                    return ResponseEntity.ok(handleGetStatement(id, params));
                case "ChangePassword":
                    return ResponseEntity.ok(buildSuccessResponse(id, Map.of("result", "success")));
                default:
                    return ResponseEntity.ok(buildErrorResponse(id, 603, "Неправильный код команды"));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(buildErrorResponse(id, 102, "Системная ошибка: " + e.getMessage()));
        }
    }

    private Map<String, Object> handleGetInformation(Object id, Map<String, Object> params) {
        Map<String, Object> fields = (Map<String, Object>) params.get("fields");
        User driver = findDriverFromFields(fields);

        if (driver == null) {
            return buildErrorResponse(id, 302, "Клиент не найден");
        }

        BigDecimal balanceUzs = driver.getBalance() != null ? driver.getBalance() : BigDecimal.ZERO;
        long balanceTiyin = balanceUzs.multiply(BigDecimal.valueOf(100)).longValue();

        Map<String, Object> resultFields = new HashMap<>();
        resultFields.put("name", driver.getFullName() != null ? driver.getFullName() : "Haydovchi");
        resultFields.put("balance", balanceTiyin);
        resultFields.put("phone", driver.getPhone());
        resultFields.put("driverId", driver.getDriverId());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "0");
        result.put("timestamp", LocalDateTime.now().format(DATE_FORMATTER));
        result.put("fields", resultFields);

        return buildSuccessResponse(id, result);
    }

    private Map<String, Object> handlePerformTransaction(Object id, Map<String, Object> params) {
        if (!params.containsKey("transactionId") || !params.containsKey("amount")) {
            return buildErrorResponse(id, 411, "Не заданы один или несколько обязательных параметров");
        }

        Long paynetTrnId = Long.valueOf(params.get("transactionId").toString());
        Long amountTiyin = Long.valueOf(params.get("amount").toString());

        if (amountTiyin <= 0) {
            return buildErrorResponse(id, 413, "Неверная сумма");
        }

        // Check if transaction already exists
        Optional<PaynetTransaction> existingTrn = paynetTransactionRepository.findByPaynetTransactionId(paynetTrnId);
        if (existingTrn.isPresent()) {
            PaynetTransaction trn = existingTrn.get();
            if (trn.getState() == 1) {
                User driver = trn.getUser();
                long balanceTiyin = (driver.getBalance() != null ? driver.getBalance() : BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(100)).longValue();

                Map<String, Object> result = new HashMap<>();
                result.put("status", "0");
                result.put("timestamp", trn.getCreatedAt().format(DATE_FORMATTER));
                result.put("providerTrnId", trn.getId());
                result.put("fields", Map.of("balance", balanceTiyin, "name", driver.getFullName()));
                return buildSuccessResponse(id, result);
            } else {
                return buildErrorResponse(id, 201, "Транзакция уже существует");
            }
        }

        Map<String, Object> fields = (Map<String, Object>) params.get("fields");
        User driver = findDriverFromFields(fields);
        if (driver == null) {
            return buildErrorResponse(id, 302, "Клиент не найден");
        }

        // Convert tiyin to UZS
        BigDecimal amountUzs = BigDecimal.valueOf(amountTiyin).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal currentBalance = driver.getBalance() != null ? driver.getBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.add(amountUzs);
        driver.setBalance(newBalance);

        userRepository.save(driver);

        PaynetTransaction trn = PaynetTransaction.builder()
                .paynetTransactionId(paynetTrnId)
                .serviceId(params.get("serviceId") != null ? Integer.valueOf(params.get("serviceId").toString()) : 1)
                .amount(amountTiyin)
                .state(1)
                .user(driver)
                .build();

        PaynetTransaction savedTrn = paynetTransactionRepository.save(trn);

        long newBalanceTiyin = newBalance.multiply(BigDecimal.valueOf(100)).longValue();
        Map<String, Object> resultFields = Map.of(
                "balance", newBalanceTiyin,
                "name", driver.getFullName() != null ? driver.getFullName() : "Haydovchi"
        );

        Map<String, Object> result = new HashMap<>();
        result.put("status", "0");
        result.put("timestamp", LocalDateTime.now().format(DATE_FORMATTER));
        result.put("providerTrnId", savedTrn.getId());
        result.put("fields", resultFields);

        return buildSuccessResponse(id, result);
    }

    private Map<String, Object> handleCheckTransaction(Object id, Map<String, Object> params) {
        if (!params.containsKey("transactionId")) {
            return buildErrorResponse(id, 411, "Не заданы один или несколько обязательных параметров");
        }

        Long paynetTrnId = Long.valueOf(params.get("transactionId").toString());
        Optional<PaynetTransaction> trnOpt = paynetTransactionRepository.findByPaynetTransactionId(paynetTrnId);

        if (trnOpt.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("transactionState", 3); // 3 = Not Found
            result.put("timestamp", LocalDateTime.now().format(DATE_FORMATTER));
            return buildSuccessResponse(id, result);
        }

        PaynetTransaction trn = trnOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("providerTrnId", trn.getId());
        result.put("timestamp", trn.getCreatedAt().format(DATE_FORMATTER));
        result.put("transactionState", trn.getState()); // 1 = Success, 2 = Cancelled

        return buildSuccessResponse(id, result);
    }

    private Map<String, Object> handleCancelTransaction(Object id, Map<String, Object> params) {
        if (!params.containsKey("transactionId")) {
            return buildErrorResponse(id, 411, "Не заданы один или несколько обязательных параметров");
        }

        Long paynetTrnId = Long.valueOf(params.get("transactionId").toString());
        Optional<PaynetTransaction> trnOpt = paynetTransactionRepository.findByPaynetTransactionId(paynetTrnId);

        if (trnOpt.isEmpty()) {
            return buildErrorResponse(id, 302, "Транзакция не найдена");
        }

        PaynetTransaction trn = trnOpt.get();
        if (trn.getState() == 2) {
            Map<String, Object> result = Map.of(
                    "providerTrnId", trn.getId(),
                    "timestamp", trn.getCancelTime() != null ? trn.getCancelTime().format(DATE_FORMATTER) : LocalDateTime.now().format(DATE_FORMATTER),
                    "transactionState", 2
            );
            return buildSuccessResponse(id, result);
        }

        User driver = trn.getUser();
        BigDecimal amountUzs = BigDecimal.valueOf(trn.getAmount()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal currentBalance = driver.getBalance() != null ? driver.getBalance() : BigDecimal.ZERO;

        if (currentBalance.compareTo(amountUzs) < 0) {
            return buildErrorResponse(id, 77, "Недостаточно средств на счету клиента для отмены платежа");
        }

        driver.setBalance(currentBalance.subtract(amountUzs));
        userRepository.save(driver);

        trn.setState(2);
        trn.setCancelTime(LocalDateTime.now());
        paynetTransactionRepository.save(trn);

        Map<String, Object> result = Map.of(
                "providerTrnId", trn.getId(),
                "timestamp", trn.getCancelTime().format(DATE_FORMATTER),
                "transactionState", 2
        );

        return buildSuccessResponse(id, result);
    }

    private Map<String, Object> handleGetStatement(Object id, Map<String, Object> params) {
        String dateFromStr = params.get("dateFrom") != null ? params.get("dateFrom").toString() : null;
        String dateToStr = params.get("dateTo") != null ? params.get("dateTo").toString() : null;

        LocalDateTime dateFrom = dateFromStr != null ? LocalDateTime.parse(dateFromStr, DATE_FORMATTER) : LocalDateTime.now().minusDays(1);
        LocalDateTime dateTo = dateToStr != null ? LocalDateTime.parse(dateToStr, DATE_FORMATTER) : LocalDateTime.now();

        List<PaynetTransaction> trns = paynetTransactionRepository.findByCreatedAtBetween(dateFrom, dateTo);

        List<Map<String, Object>> statementList = new ArrayList<>();
        for (PaynetTransaction t : trns) {
            Map<String, Object> item = new HashMap<>();
            item.put("amount", t.getAmount());
            item.put("providerTrnId", t.getId());
            item.put("transactionId", t.getPaynetTransactionId());
            item.put("timestamp", t.getCreatedAt().format(DATE_FORMATTER));
            statementList.add(item);
        }

        Map<String, Object> result = Map.of("statements", statementList);
        return buildSuccessResponse(id, result);
    }

    private User findDriverFromFields(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return null;

        String phone = fields.get("phone") != null ? fields.get("phone").toString().trim() : null;
        String driverId = fields.get("driverId") != null ? fields.get("driverId").toString().trim() : null;
        String clientId = fields.get("client_id") != null ? fields.get("client_id").toString().trim() : null;
        String account = fields.get("account") != null ? fields.get("account").toString().trim() : null;

        if (phone != null && !phone.isEmpty()) {
            Optional<User> u = userRepository.findByPhone(phone);
            if (u.isPresent()) return u.get();
        }
        if (driverId != null && !driverId.isEmpty()) {
            Optional<User> u = userRepository.findByDriverId(driverId);
            if (u.isPresent()) return u.get();
        }
        if (clientId != null && !clientId.isEmpty()) {
            Optional<User> u = userRepository.findByDriverId(clientId);
            if (u.isPresent()) return u.get();
            try {
                Optional<User> u2 = userRepository.findById(Long.valueOf(clientId));
                if (u2.isPresent()) return u2.get();
            } catch (Exception ignored) {}
        }
        if (account != null && !account.isEmpty()) {
            Optional<User> u = userRepository.findByPhone(account);
            if (u.isPresent()) return u.get();
            Optional<User> u2 = userRepository.findByDriverId(account);
            if (u2.isPresent()) return u2.get();
        }
        return null;
    }

    private Map<String, Object> buildSuccessResponse(Object id, Object result) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> buildErrorResponse(Object id, int errorCode, String errorMessage) {
        Map<String, Object> errorObj = Map.of(
                "code", errorCode,
                "message", errorMessage
        );
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", errorObj);
        return response;
    }
}
