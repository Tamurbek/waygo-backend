package com.waygo.backend.repository;

import com.waygo.backend.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralRepository extends JpaRepository<Referral, Long> {
    List<Referral> findByInviterId(Long inviterId);
    Optional<Referral> findByInviteeId(Long inviteeId);
}
