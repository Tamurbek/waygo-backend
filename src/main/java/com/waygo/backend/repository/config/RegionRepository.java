package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegionRepository extends JpaRepository<Region, Long> {
    List<Region> findAllByIsActiveTrue();

    @Query("SELECT DISTINCT r FROM Region r LEFT JOIN FETCH r.districts ORDER BY r.name")
    List<Region> findAllWithDistricts();
}
