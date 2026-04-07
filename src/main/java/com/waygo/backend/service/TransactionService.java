package com.waygo.backend.service;

import com.waygo.backend.entity.Transaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.exception.InsufficientBalanceException;
import com.waygo.backend.exception.ResourceNotFoundException;
import com.waygo.backend.repository.TransactionRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public Transaction processPayment(Long senderId, Long receiverId, BigDecimal amount) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sender not found with id: " + senderId));
        
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Receiver not found with id: " + receiverId));

        if (sender.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for transaction. Required: " + amount + ", Available: " + sender.getBalance());
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

    public List<Transaction> getUserTransactions(Long userId) {
        return transactionRepository.findBySenderIdOrReceiverIdOrderByCreatedAtDesc(userId, userId);
    }
}
