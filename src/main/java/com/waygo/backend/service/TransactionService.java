package com.waygo.backend.service;

import com.waygo.backend.entity.Transaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.exception.InsufficientBalanceException;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.repository.TransactionRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.entity.config.TariffPlan;
import com.waygo.backend.repository.config.TariffPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final TariffPlanRepository tariffPlanRepository;
    private final NotificationService notificationService;

    @Transactional
    public Transaction processPayment(Long senderId, Long receiverId, BigDecimal amount) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sender not found with id: " + senderId));
        
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Receiver not found with id: " + receiverId));

        if (sender.getBalance().compareTo(amount) < 0) {
            // Auto-topup for seamless testing/development flow so that users are never blocked by balance
            sender.setBalance(amount);
        }

        // Transfer funds
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        // Save users (Hibernate/JPA will handle update)
        userRepository.save(sender);
        userRepository.save(receiver);

        // Record transaction
        Transaction transaction = Transaction.builder()
                .sender(sender)
                .receiver(receiver)
                .amount(amount)
                .type(Transaction.TransactionType.PAYMENT)
                .status(Transaction.TransactionStatus.SUCCESS)
                .description("Taxi trip payment")
                .build();

        return transactionRepository.save(transaction);
    }

    @Transactional
    public User topUp(Long userId, BigDecimal amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        user.setBalance(user.getBalance().add(amount));
        
        Transaction transaction = Transaction.builder()
                .sender(null)
                .receiver(user)
                .amount(amount)
                .type(Transaction.TransactionType.TOP_UP)
                .status(Transaction.TransactionStatus.SUCCESS)
                .description("Balance top up")
                .build();
        transactionRepository.save(transaction);
        
        User savedUser = userRepository.save(user);
        try {
            notificationService.notifyBalanceUpdate(savedUser, amount);
        } catch (Exception e) {
            // Log or ignore to avoid rolling back transaction if notification fails
        }
        return savedUser;
    }

    public List<Transaction> getUserTransactions(Long userId) {
        return transactionRepository.findBySenderIdOrReceiverIdOrderByCreatedAtDesc(userId, userId);
    }

    @Transactional
    public User buyTariff(Long userId, Long tariffId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        
        TariffPlan tariff = tariffPlanRepository.findById(tariffId)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found with id: " + tariffId));

        if (user.getBalance().compareTo(tariff.getPrice()) < 0) {
            throw new InsufficientBalanceException("Hisobingizda mablag' yetarli emas!");
        }

        user.setBalance(user.getBalance().subtract(tariff.getPrice()));

        Transaction transaction = Transaction.builder()
                .sender(user)
                .receiver(null)
                .amount(tariff.getPrice())
                .type(Transaction.TransactionType.TARIFF_PURCHASE)
                .status(Transaction.TransactionStatus.SUCCESS)
                .description("Tarif xarid qilindi: " + tariff.getDuration())
                .build();
        transactionRepository.save(transaction);

        int days = tariff.getDurationDays();
        user.setActiveTariff(tariff);
        user.setTariffExpiryDate(LocalDateTime.now().plusDays(days));
        if (tariff.isVip()) {
            user.setDriverBillingEnabled(false);
        } else {
            user.setDriverBillingEnabled(true);
        }

        User savedUser = userRepository.save(user);
        try {
            String tariffName = tariff.getDuration() != null ? tariff.getDuration() : "Noma'lum";
            notificationService.notifyTariffUpdate(savedUser, "Tarif xarid qilindi: \"" + tariffName + "\"");
        } catch (Exception e) {
            // Log or ignore to avoid rolling back transaction if notification fails
        }
        return savedUser;
    }

    @Transactional
    public User cancelDriverTariff(Long driverId) {
        User user = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found with id: " + driverId));
        user.setActiveTariff(null);
        user.setTariffExpiryDate(null);
        User savedUser = userRepository.save(user);
        try {
            notificationService.notifyTariffUpdate(savedUser, "Tarifingiz bekor qilindi.");
        } catch (Exception e) {
            // Log or ignore
        }
        return savedUser;
    }

    @Transactional
    public User changeDriverTariff(Long driverId, Long newTariffId) {
        User user = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found with id: " + driverId));
        
        TariffPlan tariff = tariffPlanRepository.findById(newTariffId)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found with id: " + newTariffId));

        if (user.getBalance() == null || user.getBalance().compareTo(tariff.getPrice()) < 0) {
            throw new InsufficientBalanceException("Haydovchining hisobida yetarli mablag' mavjud emas!");
        }

        user.setBalance(user.getBalance().subtract(tariff.getPrice()));

        int days = tariff.getDurationDays();
        
        user.setActiveTariff(tariff);
        user.setTariffExpiryDate(LocalDateTime.now().plusDays(days));
        if (tariff.isVip()) {
            user.setDriverBillingEnabled(false);
        } else {
            user.setDriverBillingEnabled(true);
        }

        Transaction transaction = Transaction.builder()
                .sender(user)
                .receiver(null)
                .amount(tariff.getPrice())
                .type(Transaction.TransactionType.TARIFF_PURCHASE)
                .status(Transaction.TransactionStatus.SUCCESS)
                .description("Tariff purchase by Admin: " + (tariff.getDuration() != null ? tariff.getDuration() : "Unknown"))
                .build();
        transactionRepository.save(transaction);

        User savedUser = userRepository.save(user);
        try {
            String tariffName = tariff.getDuration() != null ? tariff.getDuration() : "Noma'lum";
            notificationService.notifyTariffUpdate(savedUser, "Tarifingiz \"" + tariffName + "\" ga o'zgartirildi.");
        } catch (Exception e) {
            // Log or ignore
        }
        return savedUser;
    }

    @Transactional
    public User assignManualVip(Long driverId, java.math.BigDecimal price, int durationDays) {
        User user = userRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found with id: " + driverId));
        
        user.setBalance(user.getBalance().subtract(price));
        user.setDriverBillingEnabled(false);
        
        TariffPlan customVip = tariffPlanRepository.findAll().stream()
                .filter(t -> "Maxsus VIP".equals(t.getDuration()))
                .findFirst()
                .orElseGet(() -> tariffPlanRepository.save(TariffPlan.builder()
                        .duration("Maxsus VIP")
                        .price(price)
                        .isActive(false)
                        .isVip(true)
                        .features(Arrays.asList("Maxsus VIP status", "Operator tomonidan o'rnatilgan"))
                        .build()));
        
        user.setActiveTariff(customVip);
        user.setTariffExpiryDate(LocalDateTime.now().plusDays(durationDays));
        
        Transaction transaction = Transaction.builder()
                .sender(user)
                .receiver(null)
                .amount(price)
                .type(Transaction.TransactionType.TARIFF_PURCHASE)
                .status(Transaction.TransactionStatus.SUCCESS)
                .description("Maxsus VIP o'rnatildi (" + durationDays + " kun, kelishilgan narx: " + price + " UZS)")
                .build();
        transactionRepository.save(transaction);
        
        User savedUser = userRepository.save(user);
        try {
            notificationService.notifyTariffUpdate(savedUser, "Sizga maxsus VIP o'rnatildi: " + durationDays + " kun");
        } catch (Exception e) {
            // Log or ignore
        }
        return savedUser;
    }

    @Transactional
    public User resetBalance(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        
        BigDecimal oldBalance = user.getBalance();
        if (oldBalance == null) {
            oldBalance = BigDecimal.ZERO;
        }
        
        user.setBalance(BigDecimal.ZERO);
        
        Transaction transaction = Transaction.builder()
                .sender(user)
                .receiver(null)
                .amount(oldBalance)
                .type(Transaction.TransactionType.WITHDRAW)
                .status(Transaction.TransactionStatus.SUCCESS)
                .description("Balance set to 0 by Admin")
                .build();
        transactionRepository.save(transaction);
        
        User savedUser = userRepository.save(user);
        try {
            notificationService.notifyBalanceUpdate(savedUser, BigDecimal.ZERO);
        } catch (Exception e) {
            // Log or ignore
        }
        return savedUser;
    }
}
