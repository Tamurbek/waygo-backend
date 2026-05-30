package com.waygo.backend;

import com.waygo.backend.dto.PaymeRequest;
import com.waygo.backend.dto.PaymeResponse;
import com.waygo.backend.entity.PaymeTransaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PaymeTransactionRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.service.PaymeService;
import com.waygo.backend.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymeServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymeTransactionRepository paymeTransactionRepository;

    @Mock
    private TransactionService transactionService;

    @InjectMocks
    private PaymeService paymeService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(paymeService, "login", "Paycom");
        ReflectionTestUtils.setField(paymeService, "merchantKey", "test_key");
    }

    @Test
    void testAuthorize_Success() {
        // Base64 of Paycom:test_key is UGF5Y29tOnRlc3Rfa2V5
        String header = "Basic UGF5Y29tOnRlc3Rfa2V5";
        assertTrue(paymeService.authorize(header));
    }

    @Test
    void testAuthorize_Failure() {
        String header = "Basic invalid_base64";
        assertFalse(paymeService.authorize(header));
        assertFalse(paymeService.authorize(null));
    }

    @Test
    void testCheckPerformTransaction_UserNotFound() {
        when(userRepository.findByDriverId("WG1234567")).thenReturn(Optional.empty());
        when(userRepository.findById(1234567L)).thenReturn(Optional.empty());

        PaymeRequest request = new PaymeRequest();
        request.setMethod("CheckPerformTransaction");
        request.setId(123L);

        Map<String, Object> params = new HashMap<>();
        params.put("amount", 5000000L);
        params.put("account", Map.of("user_id", "WG1234567"));
        request.setParams(params);

        PaymeResponse response = paymeService.process(request);

        assertNotNull(response.getError());
        assertEquals(-31001, response.getError().getCode());
        assertEquals(123L, response.getId());
    }

    @Test
    void testCheckPerformTransaction_By6DigitDriverId_Success() {
        User user = User.builder().id(1L).driverId("WG6543210").fullName("Test Driver").role(User.Role.DRIVER).build();
        when(userRepository.findByDriverId("WG6543210")).thenReturn(Optional.of(user));

        PaymeRequest request = new PaymeRequest();
        request.setMethod("CheckPerformTransaction");
        request.setId(123L);

        Map<String, Object> params = new HashMap<>();
        params.put("amount", 5000000L);
        params.put("account", Map.of("user_id", "wg6543210")); // testing lowercase input case insensitivity
        request.setParams(params);

        PaymeResponse response = paymeService.process(request);

        assertNull(response.getError());
        assertNotNull(response.getResult());
        
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertEquals(true, result.get("allow"));
    }

    @Test
    void testCheckPerformTransaction_ByDbIdFallback_Success() {
        User user = User.builder().id(99L).fullName("Test Passenger").role(User.Role.PASSENGER).build();
        when(userRepository.findByDriverId("99")).thenReturn(Optional.empty());
        when(userRepository.findById(99L)).thenReturn(Optional.of(user));

        PaymeRequest request = new PaymeRequest();
        request.setMethod("CheckPerformTransaction");
        request.setId(123L);

        Map<String, Object> params = new HashMap<>();
        params.put("amount", 5000000L);
        params.put("account", Map.of("user_id", "99"));
        request.setParams(params);

        PaymeResponse response = paymeService.process(request);

        assertNull(response.getError());
        assertNotNull(response.getResult());
        
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertEquals(true, result.get("allow"));
    }

    @Test
    void testCreateTransaction_NewSuccess() {
        User user = User.builder().id(1L).driverId("WG6543210").fullName("Test Driver").role(User.Role.DRIVER).build();
        when(userRepository.findByDriverId("WG6543210")).thenReturn(Optional.of(user));
        when(paymeTransactionRepository.findByPaymeId("tx123")).thenReturn(Optional.empty());
        when(paymeTransactionRepository.save(any(PaymeTransaction.class))).thenAnswer(i -> {
            PaymeTransaction tx = i.getArgument(0);
            tx.setId(77L);
            return tx;
        });

        PaymeRequest request = new PaymeRequest();
        request.setMethod("CreateTransaction");
        request.setId(123L);

        Map<String, Object> params = new HashMap<>();
        params.put("id", "tx123");
        params.put("time", 1600000000000L);
        params.put("amount", 5000000L);
        params.put("account", Map.of("user_id", "WG6543210"));
        request.setParams(params);

        PaymeResponse response = paymeService.process(request);

        assertNull(response.getError());
        assertNotNull(response.getResult());
        
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertEquals("77", result.get("transaction"));
        assertEquals(1, result.get("state"));
        verify(paymeTransactionRepository, times(1)).save(any(PaymeTransaction.class));
    }

    @Test
    void testPerformTransaction_Success() {
        User user = User.builder().id(1L).driverId("WG6543210").fullName("Test Driver").role(User.Role.DRIVER).balance(BigDecimal.ZERO).build();
        PaymeTransaction tx = PaymeTransaction.builder()
                .id(77L)
                .paymeId("tx123")
                .amount(5000000L)
                .state(1)
                .user(user)
                .createTime(System.currentTimeMillis())
                .build();

        when(paymeTransactionRepository.findByPaymeId("tx123")).thenReturn(Optional.of(tx));

        PaymeRequest request = new PaymeRequest();
        request.setMethod("PerformTransaction");
        request.setId(123L);

        Map<String, Object> params = new HashMap<>();
        params.put("id", "tx123");
        request.setParams(params);

        PaymeResponse response = paymeService.process(request);

        assertNull(response.getError());
        assertNotNull(response.getResult());
        
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertEquals("77", result.get("transaction"));
        assertEquals(2, result.get("state"));
        
        verify(transactionService, times(1)).topUp(1L, BigDecimal.valueOf(50000L));
        verify(paymeTransactionRepository, times(1)).save(tx);
    }

    @Test
    void testUser_AutoGenerateDriverId_PrePersist() {
        User user = User.builder()
                .fullName("New Driver")
                .role(User.Role.DRIVER)
                .build();

        assertNull(user.getDriverId());
        
        // Invoke lifecycle hook manually to test
        user.generateDriverId();
        
        assertNotNull(user.getDriverId());
        assertEquals(9, user.getDriverId().length());
        assertTrue(user.getDriverId().matches("WG\\d{7}")); // exactly WG followed by 7 digits
    }

    @Test
    void testUser_NoGenerateDriverId_ForPassenger() {
        User user = User.builder()
                .fullName("New Passenger")
                .role(User.Role.PASSENGER)
                .build();

        assertNull(user.getDriverId());
        
        user.generateDriverId();
        
        assertNull(user.getDriverId());
    }
}
