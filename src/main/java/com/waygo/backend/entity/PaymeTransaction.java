package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "payme_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymeTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payme_id", unique = true, nullable = false)
    private String paymeId;

    @Column(name = "payme_time", nullable = false)
    private Long time;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private Integer state;

    private Integer reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private Long createTime;

    private Long performTime;

    private Long cancelTime;
}
