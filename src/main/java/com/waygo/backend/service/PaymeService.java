package com.waygo.backend.service;

import com.waygo.backend.dto.PaymeRequest;
import com.waygo.backend.dto.PaymeResponse;
import com.waygo.backend.entity.PaymeTransaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.exception.PaymeException;
import com.waygo.backend.repository.PaymeTransactionRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymeService {

    private final UserRepository userRepository;
    private final PaymeTransactionRepository paymeTransactionRepository;
    private final TransactionService transactionService;

    @Value("${waygo.payme.merchant-key}")
    private String merchantKey;

    @Value("${waygo.payme.login}")
    private String login;

    public boolean authorize(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        try {
            String base64Credentials = authHeader.substring(6);
            byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(decodedBytes, StandardCharsets.UTF_8);
            String[] values = credentials.split(":", 2);
            if (values.length == 2) {
                return login.equals(values[0]) && merchantKey.equals(values[1]);
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    @Transactional
    public PaymeResponse process(PaymeRequest request) {
        try {
            if (request.getMethod() == null) {
                throw new PaymeException(-32600, "Метод не указан", "Metod ko'rsatilmagan", "Method not specified", null);
            }

            Object result;
            switch (request.getMethod()) {
                case "CheckPerformTransaction":
                    result = checkPerformTransaction(request.getParams());
                    break;
                case "CreateTransaction":
                    result = createTransaction(request.getParams());
                    break;
                case "PerformTransaction":
                    result = performTransaction(request.getParams());
                    break;
                case "CancelTransaction":
                    result = cancelTransaction(request.getParams());
                    break;
                case "CheckTransaction":
                    result = checkTransaction(request.getParams());
                    break;
                case "GetStatement":
                    result = getStatement(request.getParams());
                    break;
                default:
                    throw new PaymeException(-32601, "Метод не найден", "Metod topilmadi", "Method not found", null);
            }
            return PaymeResponse.builder().result(result).id(request.getId()).build();
        } catch (PaymeException e) {
            return PaymeResponse.builder()
                    .error(PaymeResponse.PaymeError.builder()
                            .code(e.getCode())
                            .message(e.getErrorMessage())
                            .data(e.getData())
                            .build())
                    .id(request.getId())
                    .build();
        } catch (Exception e) {
            return PaymeResponse.builder()
                    .error(PaymeResponse.PaymeError.builder()
                            .code(-32603)
                            .message(Map.of("ru", "Внутренняя ошибка", "uz", "Ichki xatolik", "en", "Internal error"))
                            .data(e.getMessage())
                            .build())
                    .id(request.getId())
                    .build();
        }
    }

    private Map<String, Object> checkPerformTransaction(Map<String, Object> params) {
        if (params == null) {
            throw new PaymeException(-32602, "Отсутствуют параметры", "Parametrlar topilmadi", "Missing parameters", null);
        }
        Long amount = getLongParam(params, "amount");
        if (amount <= 0) {
            throw new PaymeException(-31003, "Неверная сумма", "Noto'g'ri summa", "Invalid amount", "amount");
        }

        Map<String, Object> account = getAccountParam(params);
        User user = findUserByAccount(account);

        return Map.of(
            "allow", true,
            "detail", Map.of(
                "receipt", List.of(
                    Map.of(
                        "title", Map.of(
                            "ru", "Оплата за подписку Waygo (ID: " + user.getDriverId() + ")",
                            "uz", "Waygo xizmati uchun to'lov (ID: " + (user.getDriverId() != null ? user.getDriverId() : user.getId()) + ")",
                            "en", "Payment for Waygo services (ID: " + (user.getDriverId() != null ? user.getDriverId() : user.getId()) + ")"
                        ),
                        "price", amount,
                        "count", 1
                    )
                )
            )
        );
    }

    private Map<String, Object> createTransaction(Map<String, Object> params) {
        if (params == null) {
            throw new PaymeException(-32602, "Отсутствуют параметры", "Parametrlar topilmadi", "Missing parameters", null);
        }
        String id = getStringParam(params, "id");
        Long time = getLongParam(params, "time");
        Long amount = getLongParam(params, "amount");
        if (amount <= 0) {
            throw new PaymeException(-31003, "Неверная сумма", "Noto'g'ri summa", "Invalid amount", "amount");
        }

        Map<String, Object> account = getAccountParam(params);
        User user = findUserByAccount(account);

        Optional<PaymeTransaction> existingTxOpt = paymeTransactionRepository.findByPaymeId(id);

        if (existingTxOpt.isPresent()) {
            PaymeTransaction tx = existingTxOpt.get();
            if (tx.getState() == 1) {
                // Check for expiration (12 hours)
                if (System.currentTimeMillis() - tx.getCreateTime() > 43200000) {
                    tx.setState(-1);
                    tx.setCancelTime(System.currentTimeMillis());
                    tx.setReason(4);
                    paymeTransactionRepository.save(tx);
                    throw new PaymeException(-31008, "Срок действия транзакции истек", "Tranzaksiya muddati tugadi", "Transaction expired", "id");
                }
                // Verify matching parameters
                if (!tx.getUser().getId().equals(user.getId()) || !tx.getAmount().equals(amount)) {
                    throw new PaymeException(-31008, "Несоответствие параметров транзакции", "Tranzaksiya parametrlari mos kelmadi", "Transaction parameters mismatch", "id");
                }
                return Map.of(
                    "create_time", tx.getCreateTime(),
                    "transaction", tx.getId().toString(),
                    "state", 1
                );
            } else {
                throw new PaymeException(-31008, "Транзакция уже завершена или отменена", "Tranzaksiya allaqachon yakunlangan yoki bekor qilingan", "Transaction already finished or cancelled", "id");
            }
        }

        // Create new transaction
        PaymeTransaction tx = PaymeTransaction.builder()
                .paymeId(id)
                .time(time)
                .amount(amount)
                .state(1)
                .user(user)
                .createTime(System.currentTimeMillis())
                .build();

        paymeTransactionRepository.save(tx);

        return Map.of(
            "create_time", tx.getCreateTime(),
            "transaction", tx.getId().toString(),
            "state", 1
        );
    }

    private Map<String, Object> performTransaction(Map<String, Object> params) {
        if (params == null) {
            throw new PaymeException(-32602, "Отсутствуют параметры", "Parametrlar topilmadi", "Missing parameters", null);
        }
        String id = getStringParam(params, "id");

        PaymeTransaction tx = paymeTransactionRepository.findByPaymeId(id)
                .orElseThrow(() -> new PaymeException(-31003, "Транзакция не найдена", "Tranzaksiya topilmadi", "Transaction not found", "id"));

        if (tx.getState() == 1) {
            // Check for expiration (12 hours)
            if (System.currentTimeMillis() - tx.getCreateTime() > 43200000) {
                tx.setState(-1);
                tx.setCancelTime(System.currentTimeMillis());
                tx.setReason(4);
                paymeTransactionRepository.save(tx);
                throw new PaymeException(-31008, "Срок действия транзакции истек", "Tranzaksiya muddati tugadi", "Transaction expired", "id");
            }

            // Perform transaction: Top up user balance
            tx.setState(2);
            tx.setPerformTime(System.currentTimeMillis());

            // Convert tiyin to UZS
            BigDecimal uZSAmount = BigDecimal.valueOf(tx.getAmount()).divide(BigDecimal.valueOf(100));
            transactionService.topUp(tx.getUser().getId(), uZSAmount);

            paymeTransactionRepository.save(tx);

            return Map.of(
                "transaction", tx.getId().toString(),
                "perform_time", tx.getPerformTime(),
                "state", 2
            );
        } else if (tx.getState() == 2) {
            return Map.of(
                "transaction", tx.getId().toString(),
                "perform_time", tx.getPerformTime(),
                "state", 2
            );
        } else {
            throw new PaymeException(-31008, "Невозможно выполнить отмененную транзакцию", "Bekor qilingan tranzaksiyani bajarib bo'lmaydi", "Cannot perform cancelled transaction", "id");
        }
    }

    private Map<String, Object> cancelTransaction(Map<String, Object> params) {
        if (params == null) {
            throw new PaymeException(-32602, "Отсутствуют параметры", "Parametrlar topilmadi", "Missing parameters", null);
        }
        String id = getStringParam(params, "id");
        Integer reason = getIntegerParam(params, "reason");

        PaymeTransaction tx = paymeTransactionRepository.findByPaymeId(id)
                .orElseThrow(() -> new PaymeException(-31003, "Транзакция не найдена", "Tranzaksiya topilmadi", "Transaction not found", "id"));

        if (tx.getState() == 1) {
            tx.setState(-1);
            tx.setCancelTime(System.currentTimeMillis());
            tx.setReason(reason);
            paymeTransactionRepository.save(tx);

            return Map.of(
                "transaction", tx.getId().toString(),
                "cancel_time", tx.getCancelTime(),
                "state", -1
            );
        } else if (tx.getState() == 2) {
            // Cancel already performed transaction (refund)
            // Verify if user balance is enough for refund
            BigDecimal refundAmount = BigDecimal.valueOf(tx.getAmount()).divide(BigDecimal.valueOf(100));
            User user = userRepository.findById(tx.getUser().getId())
                    .orElseThrow(() -> new PaymeException(-31001, "Пользователь не найден", "Foydalanuvchi topilmadi", "User not found", "user_id"));

            if (user.getBalance().compareTo(refundAmount) < 0) {
                throw new PaymeException(-31008, "Недостаточно средств для возврата", "Pul qaytarish uchun mablag' yetarli emas", "Insufficient balance for refund", "id");
            }

            // Deduct from balance
            user.setBalance(user.getBalance().subtract(refundAmount));
            userRepository.save(user);

            tx.setState(-2);
            tx.setCancelTime(System.currentTimeMillis());
            tx.setReason(reason);
            paymeTransactionRepository.save(tx);

            return Map.of(
                "transaction", tx.getId().toString(),
                "cancel_time", tx.getCancelTime(),
                "state", -2
            );
        } else {
            // Already cancelled (-1 or -2)
            return Map.of(
                "transaction", tx.getId().toString(),
                "cancel_time", tx.getCancelTime(),
                "state", tx.getState()
            );
        }
    }

    private Map<String, Object> checkTransaction(Map<String, Object> params) {
        if (params == null) {
            throw new PaymeException(-32602, "Отсутствуют параметры", "Parametrlar topilmadi", "Missing parameters", null);
        }
        String id = getStringParam(params, "id");

        PaymeTransaction tx = paymeTransactionRepository.findByPaymeId(id)
                .orElseThrow(() -> new PaymeException(-31003, "Транзакция не найдена", "Tranzaksiya topilmadi", "Transaction not found", "id"));

        Map<String, Object> response = new HashMap<>();
        response.put("create_time", tx.getCreateTime() != null ? tx.getCreateTime() : 0L);
        response.put("perform_time", tx.getPerformTime() != null ? tx.getPerformTime() : 0L);
        response.put("cancel_time", tx.getCancelTime() != null ? tx.getCancelTime() : 0L);
        response.put("transaction", tx.getId().toString());
        response.put("state", tx.getState());
        response.put("reason", tx.getReason() != null ? tx.getReason() : null);

        return response;
    }

    private Map<String, Object> getStatement(Map<String, Object> params) {
        if (params == null) {
            throw new PaymeException(-32602, "Отсутствуют параметры", "Parametrlar topilmadi", "Missing parameters", null);
        }
        Long from = getLongParam(params, "from");
        Long to = getLongParam(params, "to");

        List<PaymeTransaction> list = paymeTransactionRepository.findAllByTimeBetween(from, to);

        List<Map<String, Object>> transactions = list.stream().map(tx -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", tx.getPaymeId());
            map.put("time", tx.getTime());
            map.put("amount", tx.getAmount());
            map.put("account", Map.of("user_id", tx.getUser().getDriverId() != null ? tx.getUser().getDriverId() : tx.getUser().getId().toString()));
            map.put("create_time", tx.getCreateTime() != null ? tx.getCreateTime() : 0L);
            map.put("perform_time", tx.getPerformTime() != null ? tx.getPerformTime() : 0L);
            map.put("cancel_time", tx.getCancelTime() != null ? tx.getCancelTime() : 0L);
            map.put("transaction", tx.getId().toString());
            map.put("state", tx.getState());
            map.put("reason", tx.getReason() != null ? tx.getReason() : null);
            return map;
        }).collect(Collectors.toList());

        return Map.of("transactions", transactions);
    }

    private User findUserByAccount(Map<String, Object> account) {
        Object userIdObj = account.get("user_id");
        if (userIdObj == null) {
            throw new PaymeException(-32602, "Отсутствует user_id в account", "account ichida user_id topilmadi", "Missing user_id in account", "user_id");
        }
        String idStr = userIdObj.toString().trim();

        // 1. Try to find user by driverId (billing ID) - case-insensitive
        Optional<User> userOpt = userRepository.findByDriverId(idStr.toUpperCase());
        if (userOpt.isPresent()) {
            return userOpt.get();
        }

        // 2. Fallback: try to find user by database autoincrement ID if numeric
        try {
            Long dbId = Long.parseLong(idStr);
            userOpt = userRepository.findById(dbId);
            if (userOpt.isPresent()) {
                return userOpt.get();
            }
        } catch (NumberFormatException e) {
            // Ignore format exception and throw user not found below
        }

        throw new PaymeException(-31001, "Пользователь не найден", "Foydalanuvchi topilmadi", "User not found", "user_id");
    }

    // Helper methods for parameter extraction
    private String getStringParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) {
            throw new PaymeException(-32602, "Отсутствует параметр " + key, "Parametr topilmadi " + key, "Missing parameter " + key, key);
        }
        return val.toString();
    }

    private Long getLongParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) {
            throw new PaymeException(-32602, "Отсутствует параметр " + key, "Parametr topilmadi " + key, "Missing parameter " + key, key);
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            throw new PaymeException(-32602, "Неверный формат параметра " + key, "Parametr formati xato " + key, "Invalid parameter format " + key, key);
        }
    }

    private Integer getIntegerParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) {
            throw new PaymeException(-32602, "Отсутствует параметр " + key, "Parametr topilmadi " + key, "Missing parameter " + key, key);
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            throw new PaymeException(-32602, "Неверный формат параметра " + key, "Parametr formati xato " + key, "Invalid parameter format " + key, key);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAccountParam(Map<String, Object> params) {
        Object val = params.get("account");
        if (val == null || !(val instanceof Map)) {
            throw new PaymeException(-32602, "Отсутствует параметр account", "account parametri topilmadi", "Missing account parameter", "account");
        }
        return (Map<String, Object>) val;
    }
}
