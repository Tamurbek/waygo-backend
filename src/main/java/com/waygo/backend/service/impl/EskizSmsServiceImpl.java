package com.waygo.backend.service.impl;

import com.waygo.backend.entity.SystemSettings;
import com.waygo.backend.service.SmsService;
import com.waygo.backend.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service("eskizSmsService")
@RequiredArgsConstructor
public class EskizSmsServiceImpl implements SmsService {

    private final RestTemplate restTemplate;
    private final SystemSettingsService settingsService;

    @Value("${waygo.eskiz.baseUrl:https://notify.eskiz.uz/api/}")
    private String baseUrl;

    private String token;
    private LocalDateTime tokenExpiry;

    @Async
    @Override
    public void sendSms(String phone, String message) {
        log.info("Request to send SMS to {}: {}", hidePhone(phone), message);
        
        try {
            ensureAuthenticated();
            executeSendSms(phone, message);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Token expired or invalid, retrying with new token...");
            refreshToken();
            executeSendSms(phone, message);
        } catch (Exception e) {
            log.error("Failed to send professional SMS to {}: {}", phone, e.getMessage());
        }
    }

    private synchronized void ensureAuthenticated() {
        if (token == null || tokenExpiry == null || LocalDateTime.now().isAfter(tokenExpiry)) {
            refreshToken();
        }
    }

    private void refreshToken() {
        SystemSettings settings = settingsService.getSettings();
        log.info("Refreshing Eskiz API token for {}", settings.getEskizEmail());
        String url = baseUrl + "auth/login";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("email", settings.getEskizEmail());
        body.add("password", settings.getEskizPassword());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = response.getBody();
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) bodyMap.get("data");
                if (data != null && data.containsKey("token")) {
                    this.token = (String) data.get("token");
                    this.tokenExpiry = LocalDateTime.now().plusDays(25);
                    log.info("Eskiz Token refreshed successfully.");
                } else {
                    throw new RuntimeException("Login response missing token data");
                }
            } else {
                throw new RuntimeException("Login failed with status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("CRITICAL: Failed to authenticate with Eskiz SMS Gateway: {}", e.getMessage());
            throw new RuntimeException("Eskiz Authentication Error", e);
        }
    }

    private void executeSendSms(String phone, String message) {
        SystemSettings settings = settingsService.getSettings();
        String url = baseUrl + "message/sms/send";

        String cleanPhone = phone.replaceAll("\\D", "");
        if (cleanPhone.startsWith("0")) {
            cleanPhone = "998" + cleanPhone.substring(1);
        } else if (cleanPhone.length() == 9) {
            cleanPhone = "998" + cleanPhone;
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("mobile_phone", cleanPhone);
        body.add("message", message);
        body.add("from", settings.getEskizFrom());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        
        if (response.getStatusCode() == HttpStatus.OK) {
            log.info("SMS successfully dispatched to {} via Eskiz", hidePhone(cleanPhone));
        } else {
            log.error("Eskiz Gateway returned error for {}: {}", cleanPhone, response.getBody());
        }
    }

    private String hidePhone(String phone) {
        if (phone == null || phone.length() < 7) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }
}

