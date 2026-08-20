package com.waygo.backend.service;

import com.waygo.backend.entity.User;
import com.waygo.backend.repository.DriverOfferRepository;
import com.waygo.backend.repository.DriverProfileRepository;
import com.waygo.backend.repository.MulticardTransactionRepository;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.PasswordResetTokenRepository;
import com.waygo.backend.repository.PaymeTransactionRepository;
import com.waygo.backend.repository.PaynetTransactionRepository;
import com.waygo.backend.repository.RideBookingRepository;
import com.waygo.backend.repository.TransactionRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.repository.VipChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final DriverOfferRepository driverOfferRepository;
    private final RideBookingRepository rideBookingRepository;
    private final VipChatMessageRepository vipChatMessageRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PaymeTransactionRepository paymeTransactionRepository;
    private final PaynetTransactionRepository paynetTransactionRepository;
    private final MulticardTransactionRepository multicardTransactionRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public void deleteUserCompletely(User user) {
        Long userId = user.getId();

        // Records owned solely by this user get deleted outright.
        vipChatMessageRepository.deleteByDriverId(userId);
        driverOfferRepository.deleteByDriverId(userId);
        rideBookingRepository.deleteByPassengerId(userId);
        driverProfileRepository.deleteByUser(user);
        passwordResetTokenRepository.deleteByUser(user);
        paymeTransactionRepository.deleteByUserId(userId);
        paynetTransactionRepository.deleteByUserId(userId);
        multicardTransactionRepository.deleteByUserId(userId);

        // Shared records (orders, wallet transactions) are kept for the other
        // party's history — only the reference to the deleted user is cleared.
        orderRepository.clearDriverReferences(userId);
        orderRepository.clearPassengerReferences(userId);
        transactionRepository.clearSenderReferences(userId);
        transactionRepository.clearReceiverReferences(userId);

        userRepository.delete(user);
    }
}
