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

    // Same ddl-auto=update gap as backfillNullActiveFlags, for `rating`/
    // `ratingCount`: both fields carry a @Builder.Default of 5.0/0 on the
    // entity, but that only ever applies to a User built fresh through
    // User.builder() — rows that existed before these columns were added
    // have them as NULL in the DB, not 5.0/0. A driver on such a row who
    // has never been rated yet reads back NULL from every list/order
    // endpoint, and every client papers over that with its own local
    // "?? 5.0" fallback — which reads identically to a driver who really
    // does have a clean 5.0 average, silently hiding that this row was
    // never actually initialized.
    @Modifying
    @Query("UPDATE User u SET u.rating = 5.0 WHERE u.rating IS NULL")
    int backfillNullRatings();

    @Modifying
    @Query("UPDATE User u SET u.ratingCount = 0 WHERE u.ratingCount IS NULL")
    int backfillNullRatingCounts();
}
