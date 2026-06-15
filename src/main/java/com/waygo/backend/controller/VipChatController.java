package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.User;
import com.waygo.backend.entity.VipChatMessage;
import com.waygo.backend.security.SecurityUtils;
import com.waygo.backend.service.VipChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tariffs/vip-chat")
@RequiredArgsConstructor
public class VipChatController {

    private final VipChatService vipChatService;
    private final SecurityUtils securityUtils;

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<VipChatMessage>>> getHistory() {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }
        
        List<VipChatMessage> history = vipChatService.getChatHistory(user.getId());
        return ResponseEntity.ok(ApiResponse.success(history, "Muloqotlar tarixi yuklandi"));
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<VipChatMessage>> sendMessage(@RequestBody Map<String, String> payload) {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }

        String text = payload.get("messageText");
        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Xabar matni bo'sh bo'lishi mumkin emas"));
        }

        VipChatMessage msg = vipChatService.sendDriverMessage(user, text);
        return ResponseEntity.ok(ApiResponse.success(msg, "Xabar muvaffaqiyatli jo'natildi"));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(@RequestBody Map<String, Object> update) {
        vipChatService.handleTelegramWebhook(update);
        return ResponseEntity.ok().build();
    }
}
