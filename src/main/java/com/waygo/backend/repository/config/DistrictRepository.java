package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.District;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DistrictRepository extends JpaRepository<District, Long> {
    List<District> findAllByIsActiveTrue();
    List<District> findAllByRegionIdAndIsActiveTrue(Long regionId);
}
