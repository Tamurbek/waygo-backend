package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.CarBrand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CarBrandRepository extends JpaRepository<CarBrand, Long> {
    List<CarBrand> findAllByIsActiveTrue();

    @Query("SELECT DISTINCT b FROM CarBrand b LEFT JOIN FETCH b.models ORDER BY b.name")
    List<CarBrand> findAllWithModels();
}
