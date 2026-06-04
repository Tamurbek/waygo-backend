package com.waygo.backend.repository.config;

import com.waygo.backend.entity.config.ServiceOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceOptionRepository extends JpaRepository<ServiceOption, Long> {
    List<ServiceOption> findAllByIsActiveTrue();
}
