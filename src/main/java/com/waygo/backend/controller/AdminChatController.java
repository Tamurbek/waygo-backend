package com.waygo.backend.controller;

import com.waygo.backend.entity.User;
import com.waygo.backend.entity.VipChatMessage;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.repository.VipChatMessageRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.waygo.backend.service.NotificationService;

@Controller
@RequestMapping("/admin/chat")
@RequiredArgsConstructor
public class AdminChatController {

    private final VipChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @GetMapping
    public String chatPage(Model model) {
        model.addAttribute("title", "WayGO Yordam Chati");
        model.addAttribute("activeItem", "chat");
        return "admin/chat";
    }

    @GetMapping("/drivers")
    @ResponseBody
    public ResponseEntity<List<ChatDriverResponse>> getChatDrivers() {
        List<VipChatMessage> latestMessages = messageRepository.findLatestMessagesGroupedByDriver();
        
        // Sort in memory by message creation date descending (latest chat at the top)
        latestMessages.sort((m1, m2) -> m2.getCreatedAt().compareTo(m1.getCreatedAt()));

        List<ChatDriverResponse> responses = latestMessages.stream()
                .map(m -> {
                    User driver = m.getDriver();
                    return ChatDriverResponse.builder()
                            .id(driver.getId())
                            .fullName(driver.getFullName() != null ? driver.getFullName() : "Ismsiz")
                            .phone(driver.getPhone())
                            .carModel(driver.getCarModel() != null ? driver.getCarModel() : "Nomalum")
                            .carNumber(driver.getCarNumber() != null ? driver.getCarNumber() : "Nomalum")
                            .lastMessage(m.getMessageText())
                            .lastMessageTime(m.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/history/{driverId}")
    @ResponseBody
    public ResponseEntity<List<VipChatMessage>> getChatHistory(@PathVariable Long driverId) {
        List<VipChatMessage> history = messageRepository.findByDriverIdOrderByCreatedAtAsc(driverId);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/send")
    @ResponseBody
    public ResponseEntity<VipChatMessage> sendAdminMessage(@RequestBody Map<String, Object> payload) {
        Long driverId = Long.valueOf(payload.get("driverId").toString());
        String messageText = payload.get("messageText").toString();

        if (messageText == null || messageText.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found"));

        VipChatMessage msg = VipChatMessage.builder()
                .driver(driver)
                .messageText(messageText.trim())
                .sender(VipChatMessage.SenderType.ADMIN)
                .build();

        VipChatMessage saved = messageRepository.save(msg);
        notificationService.notifyNewChatMessage(saved);
        return ResponseEntity.ok(saved);
    }

    @Data
    @Builder
    public static class ChatDriverResponse {
        private Long id;
        private String fullName;
        private String phone;
        private String carModel;
        private String carNumber;
        private String lastMessage;
        private LocalDateTime lastMessageTime;
    }
}
