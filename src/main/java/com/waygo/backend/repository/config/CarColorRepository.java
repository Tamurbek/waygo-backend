package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.CarColor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CarColorRepository extends JpaRepository<CarColor, Long> {
    List<CarColor> findAllByIsActiveTrue();
}
