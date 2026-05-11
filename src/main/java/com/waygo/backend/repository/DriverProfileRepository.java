package com.waygo.backend.repository;

import com.waygo.backend.entity.DriverProfile;
import com.waygo.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, Long> {
    Optional<DriverProfile> findByUser(User user);
}
