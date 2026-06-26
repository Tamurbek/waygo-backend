package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.PointsTransaction;
import com.waygo.backend.entity.Referral;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PointsTransactionRepository;
import com.waygo.backend.repository.ReferralRepository;
import com.waygo.backend.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralRepository referralRepository;
    private final PointsTransactionRepository pointsTransactionRepository;
    private final SecurityUtils securityUtils;

    @GetMapping("/my-referrals")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyReferrals() {
        User user = securityUtils.getCurrentUser();
        List<Referral> referrals = referralRepository.findByInviterId(user.getId());

        List<Map<String, Object>> responseList = referrals.stream().map(ref -> {
            Map<String, Object> map = new HashMap<>();
            map.put("friendName", ref.getInvitee().getFullName() != null ? ref.getInvitee().getFullName() : ref.getInvitee().getPhone());
            map.put("status", ref.getStatus().name());
            map.put("points", ref.getRewardPoints());
            map.put("date", ref.getCreatedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(responseList, "Referrals fetched"));
    }

    @GetMapping("/my-points-history")
    public ResponseEntity<ApiResponse<List<PointsTransaction>>> getMyPointsHistory() {
        User user = securityUtils.getCurrentUser();
        List<PointsTransaction> history = pointsTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return ResponseEntity.ok(ApiResponse.success(history, "Points history fetched"));
    }
}
