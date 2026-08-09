package com.waygo.backend.repository;

import com.waygo.backend.entity.MapSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MapSettingsRepository extends JpaRepository<MapSettings, Long> {
    Optional<MapSettings> findFirstByOrderByIdAsc();
}
