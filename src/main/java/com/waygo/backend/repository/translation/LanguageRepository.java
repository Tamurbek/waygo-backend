package com.waygo.backend.repository.translation;

import com.waygo.backend.entity.translation.Language;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LanguageRepository extends JpaRepository<Language, Long> {
    List<Language> findAllByOrderBySortOrderAsc();

    List<Language> findAllByActiveTrueOrderBySortOrderAsc();

    Optional<Language> findByCode(String code);

    boolean existsByCode(String code);
}
