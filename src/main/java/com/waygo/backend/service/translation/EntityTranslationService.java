package com.waygo.backend.service.translation;

import com.waygo.backend.entity.translation.TranslationKey;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Bridges the generic {@link TranslationKey}/{@link com.waygo.backend.entity.translation.TranslationValue}
 * infrastructure (originally built for UI-string translations) to reference-data fields
 * (car brand/model names, service names, tariff text, region names, guide steps). Each
 * translatable field on those entities stores a nullable {@code Long} pointing at a
 * TranslationKey; this service creates/updates that key and its per-language values, and
 * resolves it back for the public API.
 */
@Service
@RequiredArgsConstructor
public class EntityTranslationService {

    private final TranslationService translationService;

    /**
     * Creates the backing TranslationKey on first use, then upserts one TranslationValue
     * per non-blank entry in {@code valuesByLangCode}. Blank/missing languages are left
     * untouched so partial translation is fine.
     *
     * @param existingKeyId the field's current key id, or null if this is the first save
     * @param keyCodeIfNew  the keyCode to create the key with, only used when existingKeyId is null
     * @param valuesByLangCode language code -> translated text, e.g. {"uz": "...", "ru": "..."}
     * @return the key id to persist back onto the owning entity's field
     */
    @Transactional
    public Long upsertField(Long existingKeyId, String keyCodeIfNew, Map<String, String> valuesByLangCode) {
        Long keyId = existingKeyId;
        if (keyId == null) {
            TranslationKey key = translationService.addKey(keyCodeIfNew, AppTarget.SHARED, null, null);
            keyId = key.getId();
        }
        if (valuesByLangCode != null) {
            for (Map.Entry<String, String> entry : valuesByLangCode.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                translationService.upsertValue(keyId, entry.getKey(), value);
            }
        }
        return keyId;
    }

    /** Pre-fills an edit form: language code -> current translated value. Empty map if keyId is null. */
    @Transactional(readOnly = true)
    public Map<String, String> valuesFor(Long keyId) {
        return translationService.valuesForKey(keyId);
    }

    /** Resolves the field's value for the given locale, falling back to the legacy plain-column value. */
    @Transactional(readOnly = true)
    public String resolve(Long keyId, String locale, String fallback) {
        return translationService.resolveKeyValue(keyId, locale).orElse(fallback);
    }

    /** Deletes the key and all its values, e.g. when a tariff feature row is removed. */
    @Transactional
    public void deleteField(Long keyId) {
        if (keyId != null) {
            translationService.deleteKey(keyId);
        }
    }
}
