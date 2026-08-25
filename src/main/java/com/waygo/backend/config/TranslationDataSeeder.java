package com.waygo.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.service.translation.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Keeps the admin-managed TranslationKey/TranslationValue tables in sync with the same ARB
 * source text compiled into the Flutter apps (copied into src/main/resources/translations-seed
 * at commit time — not read from the Flutter repos at runtime). Without this, keys the apps
 * actually use could be entirely absent from the DB, so both the apps (falling back to their
 * compiled ARB text for that key) and admin features like "Shablon yuklab olish" (template
 * export) would silently be missing them.
 *
 * Runs on every startup, not just once: for every key/language pair in the seed files that has
 * no value in the DB yet — a brand-new key the app started using, or a pre-existing key that
 * never got a uz_lotin value — it adds the seed's text. It never touches a key/language pair
 * that already has a value, so it never overwrites an admin's hand-edited translation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationDataSeeder implements CommandLineRunner {

    private static final String SEED_LANGUAGE_CODE = "uz_lotin";

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
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(new ClassPathResource(resourcePath).getInputStream(), Map.class);
            int added = translationService.addMissingKeys(appTarget, SEED_LANGUAGE_CODE, raw);
            if (added > 0) {
                log.info("Added {} missing translation keys for {} from {}", added, appTarget, resourcePath);
            }
        } catch (Exception e) {
            log.error("Failed to sync translations for {} from {}: {}", appTarget, resourcePath, e.getMessage(), e);
        }
    }
}
