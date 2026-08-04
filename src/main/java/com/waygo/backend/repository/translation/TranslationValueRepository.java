package com.waygo.backend.repository.translation;

import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.entity.translation.TranslationValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TranslationValueRepository extends JpaRepository<TranslationValue, Long> {

    @Query("SELECT v FROM TranslationValue v " +
           "JOIN FETCH v.translationKey k " +
           "JOIN FETCH v.language l " +
           "WHERE k.appTarget IN :appTargets")
    List<TranslationValue> findAllByTranslationKey_AppTargetIn(List<AppTarget> appTargets);

    Optional<TranslationValue> findByTranslationKeyIdAndLanguage_Code(Long translationKeyId, String languageCode);

    List<TranslationValue> findAllByLanguage_CodeAndTranslationKey_AppTargetIn(String languageCode, List<AppTarget> appTargets);

    @Transactional
    void deleteAllByTranslationKeyId(Long translationKeyId);

    @Transactional
    void deleteByTranslationKeyIdAndLanguage_Code(Long translationKeyId, String languageCode);
}
