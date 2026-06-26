package com.waygo.backend.service;

import com.waygo.backend.entity.PointsTransaction;
import com.waygo.backend.entity.Referral;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PointsTransactionRepository;
import com.waygo.backend.repository.ReferralRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private final ReferralRepository referralRepository;
    private final UserRepository userRepository;
    private final PointsTransactionRepository pointsTransactionRepository;

    private static final int REFERRAL_BONUS_POINTS = 1000;

    @Transactional
    public void processReferralCodeDuringRegistration(User newInvitee, String referralCode) {
        if (referralCode == null || referralCode.trim().isEmpty()) {
            return;
        }

        userRepository.findByReferralCode(referralCode).ifPresent(inviter -> {
            if (!inviter.getId().equals(newInvitee.getId())) {
                newInvitee.setReferredById(inviter.getId());
                userRepository.save(newInvitee);

                Referral referral = Referral.builder()
                        .inviter(inviter)
                        .invitee(newInvitee)
                        .status(Referral.ReferralStatus.PENDING)
                        .rewardPoints(REFERRAL_BONUS_POINTS)
                        .build();

                referralRepository.save(referral);
            }
        });
    }

    @Transactional
    public void rewardInviterIfFirstTripCompleted(User invitee) {
        if (invitee.getReferredById() == null) {
            return;
        }

        referralRepository.findByInviteeId(invitee.getId()).ifPresent(referral -> {
            if (referral.getStatus() == Referral.ReferralStatus.PENDING) {
                User inviter = referral.getInviter();

                // Add points to inviter
                int currentBalance = inviter.getPointsBalance() != null ? inviter.getPointsBalance() : 0;
                inviter.setPointsBalance(currentBalance + REFERRAL_BONUS_POINTS);
                userRepository.save(inviter);

                // Update referral status
                referral.setStatus(Referral.ReferralStatus.COMPLETED);
                referral.setCompletedAt(LocalDateTime.now());
                referralRepository.save(referral);

                // Record transaction
                PointsTransaction transaction = PointsTransaction.builder()
                        .user(inviter)
                        .amount(REFERRAL_BONUS_POINTS)
                        .type(PointsTransaction.TransactionType.REFERRAL_BONUS)
                        .description("Do'stingiz " + invitee.getFullName() + " birinchi safarini yakunlagani uchun bonus")
                        .build();
                pointsTransactionRepository.save(transaction);
            }
        });
    }
}
