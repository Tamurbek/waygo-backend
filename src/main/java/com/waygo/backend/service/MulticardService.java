package com.waygo.backend.service;

import com.waygo.backend.entity.MulticardTransaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.MulticardTransactionRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class MulticardService {

    private final UserRepository userRepository;
    private final MulticardTransactionRepository multicardTransactionRepository;
    private final TransactionService transactionService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${waygo.multicard.application-id}")
    private String applicationId;

    @Value("${waygo.multicard.secret}")
    private String secret;

    @Value("${waygo.multicard.store-id}")
    private Integer storeId;

    @Value("${waygo.multicard.base-url}")
    private String baseUrl;

    @Value("${waygo.multicard.backend-url}")
    private String backendUrl;

    private String cachedToken;
    private LocalDateTime tokenExpiry;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private synchronized String getAuthToken() {
        if (cachedToken != null && tokenExpiry != null && tokenExpiry.isAfter(LocalDateTime.now().plusMinutes(5))) {
            return cachedToken;
        }

        try {
            String url = baseUrl + "/auth";
            Map<String, String> request = Map.of(
                    "application_id", applicationId,
                    "secret", secret
            );

            log.info("Requesting new Multicard auth token from {}", url);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                cachedToken = (String) body.get("token");
                String expiryStr = (String) body.get("expiry");
                if (expiryStr != null) {
                    tokenExpiry = LocalDateTime.parse(expiryStr, DATE_FORMATTER);
                } else {
                    tokenExpiry = LocalDateTime.now().plusHours(24);
                }
                log.info("Successfully fetched Multicard token. Expires at: {}", tokenExpiry);
                return cachedToken;
            }
        } catch (Exception e) {
            log.error("Failed to fetch Multicard auth token: {}", e.getMessage(), e);
        }
        return cachedToken;
    }

    @Transactional
    public String createInvoice(User driver, BigDecimal amountUzs) {
        if (amountUzs == null || amountUzs.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Musbat summa kiritilishi shart");
        }

        String invoiceId = "MC_INV_" + System.currentTimeMillis() + "_" + driver.getId();
        long amountTiyins = amountUzs.multiply(BigDecimal.valueOf(100)).longValue();

        MulticardTransaction transaction = MulticardTransaction.builder()
                .invoiceId(invoiceId)
                .amount(amountTiyins)
                .status("draft")
                .user(driver)
                .build();
        multicardTransactionRepository.save(transaction);

        try {
            String token = getAuthToken();
            if (token == null) {
                throw new IllegalStateException("Multicard authorization token is unavailable");
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("store_id", storeId);
            requestBody.put("amount", amountTiyins);
            requestBody.put("invoice_id", invoiceId);
            requestBody.put("lang", "ru");
            requestBody.put("return_url", "https://waygo.uz/payment-result");
            requestBody.put("callback_url", backendUrl + "/api/v1/payments/multicard/callback");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            String url = baseUrl + "/payment/invoice";

            log.info("Creating Multicard invoice for driver {}, amount {} UZS", driver.getId(), amountUzs);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (Boolean.TRUE.equals(body.get("success"))) {
                    Map<String, Object> data = (Map<String, Object>) body.get("data");
                    String uuid = (String) data.get("uuid");
                    String checkoutUrl = (String) data.get("checkout_url");

                    transaction.setUuid(uuid);
                    transaction.setCheckoutUrl(checkoutUrl);
                    transaction.setStatus("progress");
                    transaction.setUpdatedAt(LocalDateTime.now());
                    multicardTransactionRepository.save(transaction);

                    return checkoutUrl;
                } else {
                    Map<String, Object> error = (Map<String, Object>) body.get("error");
                    String details = error != null ? String.valueOf(error.get("details")) : "Unknown error";
                    throw new RuntimeException("Multicard API error: " + details);
                }
            } else {
                throw new RuntimeException("Failed to call Multicard API, status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to create Multicard invoice: {}", e.getMessage(), e);
            transaction.setStatus("error");
            transaction.setUpdatedAt(LocalDateTime.now());
            multicardTransactionRepository.save(transaction);
            throw new RuntimeException("Инвойс яратишда хатолик юз берди: " + e.getMessage());
        }
    }

    @Transactional
    public void processCallback(Map<String, Object> payload) {
        log.info("Received Multicard callback: {}", payload);

        String uuid = (String) payload.get("uuid");
        String invoiceId = (String) payload.get("invoice_id");
        Object amountObj = payload.get("amount");
        String status = (String) payload.get("status");
        String sign = (String) payload.get("sign");

        if (uuid == null || invoiceId == null || amountObj == null || status == null || sign == null) {
            throw new IllegalArgumentException("Missing required callback parameters");
        }

        long amount = Long.parseLong(amountObj.toString());

        String expectedSign = sha1(uuid + invoiceId + amount + secret);
        if (!expectedSign.equalsIgnoreCase(sign)) {
            log.error("Invalid Multicard callback signature. Expected: {}, Got: {}", expectedSign, sign);
            throw new SecurityException("Invalid signature");
        }

        MulticardTransaction transaction = multicardTransactionRepository.findByInvoiceId(invoiceId)
                .orElseGet(() -> multicardTransactionRepository.findByUuid(uuid)
                        .orElseThrow(() -> new NoSuchElementException("Transaction not found for invoice_id: " + invoiceId + " or uuid: " + uuid)));

        if ("success".equals(transaction.getStatus())) {
            log.info("Multicard transaction {} is already processed successfully (idempotent)", invoiceId);
            return;
        }

        transaction.setStatus(status);
        transaction.setUpdatedAt(LocalDateTime.now());
        multicardTransactionRepository.save(transaction);

        User user = transaction.getUser();
        BigDecimal amountUzs = BigDecimal.valueOf(transaction.getAmount()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        if ("success".equals(status)) {
            log.info("Crediting driver {} balance with {} UZS from Multicard transaction {}", user.getId(), amountUzs, invoiceId);
            User updatedUser = transactionService.topUp(user.getId(), amountUzs);

            int pointsToAdd = amountUzs.divide(BigDecimal.valueOf(10), 0, RoundingMode.DOWN).intValue();
            updatedUser.setPointsBalance(updatedUser.getPointsBalance() + pointsToAdd);
            userRepository.save(updatedUser);

            log.info("Successfully completed Multicard top up for driver {}. New balance: {}, new points: {}",
                    user.getId(), updatedUser.getBalance(), updatedUser.getPointsBalance());
        } else if ("revert".equals(status)) {
            log.info("Reverting Multicard transaction {} for driver {}", invoiceId, user.getId());
            user.setBalance(user.getBalance().subtract(amountUzs));
            int pointsToDeduct = amountUzs.divide(BigDecimal.valueOf(10), 0, RoundingMode.DOWN).intValue();
            user.setPointsBalance(user.getPointsBalance() - pointsToDeduct);
            userRepository.save(user);
        }
    }

    private String sha1(String input) {
        try {
            MessageDigest mDigest = MessageDigest.getInstance("SHA-1");
            byte[] result = mDigest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : result) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("SHA1 hashing failed: {}", e.getMessage(), e);
            throw new RuntimeException("SHA1 hashing failed", e);
        }
    }
}
