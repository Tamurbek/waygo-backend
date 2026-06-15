package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vip_chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VipChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User driver;

    @Column(nullable = false, length = 1000)
    private String messageText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SenderType sender;

    private LocalDateTime createdAt;

    private Integer telegramMessageId;

    public enum SenderType {
        DRIVER,
        ADMIN
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
