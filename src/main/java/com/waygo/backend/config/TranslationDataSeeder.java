package com.waygo.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.repository.translation.TranslationKeyRepository;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.service.translation.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Populates the admin-managed TranslationKey/TranslationValue tables from the same ARB source
 * text already compiled into the Flutter apps (copied into src/main/resources/translations-seed
 * at commit time — not read from the Flutter repos at runtime). Without this, the tables start
 * empty and admin features like "Shablon yuklab olish" (template export) have no keys to offer.
 *
 * Only seeds an app the first time (skips if it already has any TranslationKey rows), so it
 * never clobbers translations an admin has since edited by hand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationDataSeeder implements CommandLineRunner {

    private static final String SEED_LANGUAGE_CODE = "uz_lotin";

    private final TranslationKeyRepository translationKeyRepository;
    private final LanguageRepository languageRepository;
    private final TranslationService translationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(String... args) {
        if (languageRepository.findByCode(SEED_LANGUAGE_CODE).isEmpty()) {
            log.warn("Translation seed skipped: language '{}' is not registered yet.", SEED_LANGUAGE_CODE);
            return;
        }
        seedApp(AppTarget.USER, "translations-seed/user_uz.json");
        seedApp(AppTarget.DRIVER, "translations-seed/driver_uz.json");
    }

    private void seedApp(AppTarget appTarget, String resourcePath) {
        if (translationKeyRepository.existsByAppTarget(appTarget)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(new ClassPathResource(resourcePath).getInputStream(), Map.class);
            int count = translationService.importJson(appTarget, SEED_LANGUAGE_CODE, raw);
            log.info("Seeded {} translation keys for {} from {}", count, appTarget, resourcePath);
        } catch (Exception e) {
            log.error("Failed to seed translations for {} from {}: {}", appTarget, resourcePath, e.getMessage(), e);
        }
    }
}
