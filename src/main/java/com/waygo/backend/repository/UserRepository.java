package com.waygo.backend.repository;

import com.waygo.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
    Optional<User> findByCarNumber(String carNumber);
    Optional<User> findByDriverId(String driverId);
    long countByRole(User.Role role);
    java.util.List<User> findByRoleOrderByCreatedAtDesc(User.Role role);

    // Rows that existed before the `active` column was added have it as NULL, not
    // true — ddl-auto=update only adds the nullable column, it doesn't backfill
    // existing rows. Called once at startup (see ActiveFlagBackfillRunner).
    @Modifying
    @Query("UPDATE User u SET u.active = true WHERE u.active IS NULL")
    int backfillNullActiveFlags();
}
