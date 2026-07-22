package com.waygo.backend;

import com.waygo.backend.entity.MulticardTransaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.MulticardTransactionRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.service.MulticardService;
import com.waygo.backend.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MulticardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MulticardTransactionRepository multicardTransactionRepository;

    @Mock
    private TransactionService transactionService;

    @InjectMocks
    private MulticardService multicardService;

    private static final String SECRET = "Pw18axeBFo8V7NamKHXX";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(multicardService, "applicationId", "rhmt_test");
        ReflectionTestUtils.setField(multicardService, "secret", SECRET);
        ReflectionTestUtils.setField(multicardService, "storeId", 6);
        ReflectionTestUtils.setField(multicardService, "baseUrl", "https://dev-mesh.multicard.uz");
        ReflectionTestUtils.setField(multicardService, "backendUrl", "https://backend.waygo.uz");
    }

    private String calculateSha1(String input) throws Exception {
        MessageDigest mDigest = MessageDigest.getInstance("SHA-1");
        byte[] result = mDigest.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : result) {
            sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    @Test
    void testProcessCallback_Success() throws Exception {
        String uuid = "uuid-12345";
        String invoiceId = "MC_INV_1784696";
        long amount = 5000000; // 50,000 UZS in tiyins
        String sign = calculateSha1(uuid + invoiceId + amount + SECRET);

        Map<String, Object> payload = new HashMap<>();
        payload.put("uuid", uuid);
        payload.put("invoice_id", invoiceId);
        payload.put("amount", amount);
        payload.put("status", "success");
        payload.put("sign", sign);

        User driver = User.builder()
                .id(1L)
                .phone("+998901234567")
                .fullName("Sherali Driver")
                .balance(BigDecimal.ZERO)
                .pointsBalance(0)
                .build();

        MulticardTransaction transaction = MulticardTransaction.builder()
                .id(10L)
                .uuid(uuid)
                .invoiceId(invoiceId)
                .amount(amount)
                .status("progress")
                .user(driver)
                .build();

        when(multicardTransactionRepository.findByInvoiceId(invoiceId)).thenReturn(Optional.of(transaction));
        when(transactionService.topUp(1L, new BigDecimal("50000.00"))).thenReturn(driver);

        // Execute
        multicardService.processCallback(payload);

        // Verify
        verify(multicardTransactionRepository, times(1)).save(transaction);
        verify(transactionService, times(1)).topUp(1L, new BigDecimal("50000.00"));
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void testProcessCallback_InvalidSignature_ThrowsException() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("uuid", "uuid-12345");
        payload.put("invoice_id", "MC_INV_1784696");
        payload.put("amount", 5000000L);
        payload.put("status", "success");
        payload.put("sign", "invalid_signature");

        assertThrows(SecurityException.class, () -> multicardService.processCallback(payload));
        verify(multicardTransactionRepository, never()).save(any());
        verify(transactionService, never()).topUp(anyLong(), any());
    }
}
