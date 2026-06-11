package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.TopUpStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TopUpStepRepository extends JpaRepository<TopUpStep, Long> {
    List<TopUpStep> findAllByOrderByStepNumberAsc();
}
