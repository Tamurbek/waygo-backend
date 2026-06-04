package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.CarModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CarModelRepository extends JpaRepository<CarModel, Long> {
    List<CarModel> findAllByIsActiveTrue();
    List<CarModel> findAllByBrandIdAndIsActiveTrue(Long brandId);
}
