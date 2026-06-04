package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.TariffPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TariffPlanRepository extends JpaRepository<TariffPlan, Long> {
    List<TariffPlan> findAllByIsActiveTrue();
}
