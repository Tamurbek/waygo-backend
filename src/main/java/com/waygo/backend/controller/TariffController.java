package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.User;
import com.waygo.backend.security.SecurityUtils;
import com.waygo.backend.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tariffs")
@RequiredArgsConstructor
public class TariffController {

    private final TransactionService transactionService;
    private final SecurityUtils securityUtils;

    @PostMapping("/buy/{tariffId}")
    public ResponseEntity<ApiResponse<User>> buyTariff(@PathVariable Long tariffId) {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }

        User updatedUser = transactionService.buyTariff(user.getId(), tariffId);
        return ResponseEntity.ok(ApiResponse.success(updatedUser, "Tarif muvaffaqiyatli xarid qilindi"));
    }
}
