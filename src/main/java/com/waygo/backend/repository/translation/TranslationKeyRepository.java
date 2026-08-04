package com.waygo.backend.repository.translation;

import com.waygo.backend.entity.translation.TranslationKey;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TranslationKeyRepository extends JpaRepository<TranslationKey, Long> {
    List<TranslationKey> findAllByAppTargetInOrderByKeyCodeAsc(List<AppTarget> appTargets);

    Optional<TranslationKey> findByKeyCodeAndAppTarget(String keyCode, AppTarget appTarget);

    boolean existsByKeyCodeAndAppTarget(String keyCode, AppTarget appTarget);
}
