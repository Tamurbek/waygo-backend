package com.waygo.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waygo.backend.entity.SystemSettings;
import com.waygo.backend.entity.User;
import com.waygo.backend.entity.VipChatMessage;
import com.waygo.backend.repository.VipChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class VipChatService {

    private final VipChatMessageRepository messageRepository;
    private final SystemSettingsService settingsService;
    private final NotificationService notificationService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<VipChatMessage> getChatHistory(Long driverId) {
        return messageRepository.findByDriverIdOrderByCreatedAtAsc(driverId);
    }

    @Transactional
    public VipChatMessage sendDriverMessage(User driver, String text) {
        VipChatMessage message = VipChatMessage.builder()
                .driver(driver)
                .messageText(text)
                .sender(VipChatMessage.SenderType.DRIVER)
                .build();
        
        VipChatMessage savedMessage = messageRepository.save(message);

        SystemSettings settings = settingsService.getSettings();
        String botToken = settings.getTelegramBotToken();
        String chatId = settings.getTelegramChatId();

        if (botToken != null && !botToken.trim().isEmpty() && chatId != null && !chatId.trim().isEmpty()) {
            try {
                String formattedText = String.format(
                        "🔔 *WayGO Support - Yangi Murojaat*\n\n" +
                        "👤 *Haydovchi:* %s\n" +
                        "📞 *Telefon:* %s\n" +
                        "🚘 *Mashina:* %s (%s)\n\n" +
                        "💬 *Murojaat:* %s",
                        driver.getFullName() != null ? driver.getFullName() : "Ismsiz",
                        driver.getPhone(),
                        driver.getCarModel() != null ? driver.getCarModel() : "Nomalum",
                        driver.getCarNumber() != null ? driver.getCarNumber() : "Nomalum",
                        text
                );

                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> body = new HashMap<>();
                body.put("chat_id", chatId);
                body.put("text", formattedText);
                body.put("parse_mode", "Markdown");

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
                String responseStr = restTemplate.postForObject(url, request, String.class);
                
                JsonNode root = objectMapper.readTree(responseStr);
                if (root.path("ok").asBoolean()) {
                    int messageId = root.path("result").path("message_id").asInt();
                    savedMessage.setTelegramMessageId(messageId);
                    messageRepository.save(savedMessage);
                }
            } catch (Exception e) {
                log.error("Failed to send VIP chat message to Telegram: {}", e.getMessage());
            }
        } else {
            log.warn("Telegram bot token or chat ID is not configured. Saved message locally only.");
        }

        return savedMessage;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handleTelegramWebhook(Map<String, Object> update) {
        try {
            Map<String, Object> messageNode = (Map<String, Object>) update.get("message");
            if (messageNode == null) return;

            Map<String, Object> replyToNode = (Map<String, Object>) messageNode.get("reply_to_message");
            if (replyToNode == null) return;

            Integer replyToMessageId = (Integer) replyToNode.get("message_id");
            if (replyToMessageId == null) return;

            String replyText = (String) messageNode.get("text");
            if (replyText == null || replyText.trim().isEmpty()) return;

            Optional<VipChatMessage> originalMessageOpt = messageRepository.findByTelegramMessageId(replyToMessageId);
            if (originalMessageOpt.isPresent()) {
                VipChatMessage original = originalMessageOpt.get();
                VipChatMessage reply = VipChatMessage.builder()
                        .driver(original.getDriver())
                        .messageText(replyText)
                        .sender(VipChatMessage.SenderType.ADMIN)
                        .build();
                VipChatMessage saved = messageRepository.save(reply);
                log.info("Received reply from Telegram admin for driver ID: {}. Text: {}", original.getDriver().getId(), replyText);
                notificationService.notifyNewChatMessage(saved);
            }
        } catch (Exception e) {
            log.error("Error processing Telegram webhook update: {}", e.getMessage(), e);
        }
    }
}
