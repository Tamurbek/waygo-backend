package com.waygo.backend.repository;

import com.waygo.backend.entity.VipChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface VipChatMessageRepository extends JpaRepository<VipChatMessage, Long> {
    List<VipChatMessage> findByDriverIdOrderByCreatedAtAsc(Long driverId);
    Optional<VipChatMessage> findByTelegramMessageId(Integer telegramMessageId);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM VipChatMessage m WHERE m.id IN (SELECT MAX(m2.id) FROM VipChatMessage m2 GROUP BY m2.driver.id)")
    List<VipChatMessage> findLatestMessagesGroupedByDriver();
}
