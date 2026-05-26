package com.waygo.backend.repository;

import com.waygo.backend.entity.DriverOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DriverOfferRepository extends JpaRepository<DriverOffer, Long> {
}
