package com.waygo.backend.service.translation;

import com.waygo.backend.entity.translation.Language;
import com.waygo.backend.entity.translation.TranslationKey;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.entity.translation.TranslationValue;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.repository.translation.TranslationKeyRepository;
import com.waygo.backend.repository.translation.TranslationValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class TranslationService {

    private final LanguageRepository languageRepository;
    private final TranslationKeyRepository translationKeyRepository;
    private final TranslationValueRepository translationValueRepository;

    /**
     * Resolves every key for the given app into a flat map, walking each language's
     * baseLanguageCode fallback chain. A key that has no value anywhere in the chain
     * is omitted entirely so the mobile client falls back to its compiled string.
     */
    @Transactional(readOnly = true)
    public Map<String, String> resolveForApp(AppTarget app, String requestedLocale) {
        List<AppTarget> targets = List.of(app, AppTarget.SHARED);
        List<TranslationKey> keys = translationKeyRepository.findAllByAppTargetInOrderByKeyCodeAsc(targets);
        List<TranslationValue> values = translationValueRepository.findAllByTranslationKey_AppTargetIn(targets);

        Map<String, String> baseOf = new HashMap<>();
        for (Language l : languageRepository.findAll()) {
            baseOf.put(l.getCode(), l.getBaseLanguageCode());
        }

        Map<Long, Map<String, String>> byKeyId = new HashMap<>();
        for (TranslationValue v : values) {
            byKeyId.computeIfAbsent(v.getTranslationKey().getId(), k -> new HashMap<>())
                   .put(v.getLanguage().getCode(), v.getValue());
        }

        Map<String, String> result = new HashMap<>();
        for (TranslationKey key : keys) {
            Map<String, String> perLang = byKeyId.getOrDefault(key.getId(), Map.of());
            String resolved = resolveChain(perLang, baseOf, requestedLocale);
            if (resolved != null) {
                result.put(key.getKeyCode(), resolved);
            }
        }
        return result;
    }

    /**
     * Resolves a single TranslationKey's value for the requested locale, walking the
     * language fallback chain (same rule as {@link #resolveForApp}). Used to translate
     * reference-data fields (car brand/model names, service names, etc.) that are linked
     * to a TranslationKey via a plain keyId column rather than a UI keyCode.
     *
     * @param keyId the TranslationKey id, or null if the field has no translations yet
     * @param requestedLocale the language code to resolve for
     * @return the resolved value, or empty if keyId is null or no value exists anywhere in the chain
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveKeyValue(Long keyId, String requestedLocale) {
        if (keyId == null || requestedLocale == null || requestedLocale.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> baseOf = new HashMap<>();
        for (Language l : languageRepository.findAll()) {
            baseOf.put(l.getCode(), l.getBaseLanguageCode());
        }
        Map<String, String> perLang = new HashMap<>();
        for (TranslationValue v : translationValueRepository.findAllByTranslationKeyId(keyId)) {
            perLang.put(v.getLanguage().getCode(), v.getValue());
        }
        return Optional.ofNullable(resolveChain(perLang, baseOf, requestedLocale));
    }

    /** Returns every language code -> value currently stored for a key. Empty map if keyId is null. */
    @Transactional(readOnly = true)
    public Map<String, String> valuesForKey(Long keyId) {
        Map<String, String> result = new HashMap<>();
        if (keyId == null) {
            return result;
        }
        for (TranslationValue v : translationValueRepository.findAllByTranslationKeyId(keyId)) {
            result.put(v.getLanguage().getCode(), v.getValue());
        }
        return result;
    }

    private String resolveChain(Map<String, String> perLang, Map<String, String> baseOf, String startLocale) {
        String lang = startLocale;
        Set<String> visited = new HashSet<>();
        while (lang != null && visited.add(lang)) {
            String v = perLang.get(lang);
            if (v != null && !v.isBlank()) {
                return v;
            }
            lang = baseOf.get(lang);
        }
        return null;
    }

    /** Throws IllegalArgumentException if setting proposedBaseCode as the base of `code` would create a cycle. */
    public void validateNoCycle(String code, String proposedBaseCode) {
        if (proposedBaseCode == null || proposedBaseCode.isBlank()) {
            return;
        }
        if (proposedBaseCode.equals(code)) {
            throw new IllegalArgumentException("Til o'zini o'ziga asos qilib bo'lmaydi");
        }
        Map<String, String> baseOf = new HashMap<>();
        for (Language l : languageRepository.findAll()) {
            baseOf.put(l.getCode(), l.getBaseLanguageCode());
        }
        // simulate the edit and walk the chain looking for a way back to `code`
        baseOf.put(code, proposedBaseCode);
        Set<String> visited = new HashSet<>();
        String lang = proposedBaseCode;
        while (lang != null) {
            if (!visited.add(lang)) {
                throw new IllegalArgumentException("Tillar orasida aylanma bog'lanish (cycle) aniqlandi");
            }
            if (lang.equals(code)) {
                throw new IllegalArgumentException("Tillar orasida aylanma bog'lanish (cycle) aniqlandi");
            }
            lang = baseOf.get(lang);
        }
    }

    @Transactional
    public void upsertValue(Long keyId, String languageCode, String value) {
        TranslationKey key = translationKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("Kalit topilmadi: " + keyId));
        Language language = languageRepository.findByCode(languageCode)
                .orElseThrow(() -> new IllegalArgumentException("Til topilmadi: " + languageCode));

        TranslationValue tv = translationValueRepository.findByTranslationKeyIdAndLanguage_Code(keyId, languageCode)
                .orElseGet(() -> TranslationValue.builder().translationKey(key).language(language).build());
        tv.setValue(value);
        translationValueRepository.save(tv);
    }

    @Transactional
    public TranslationKey addKey(String keyCode, AppTarget appTarget, String description, String screen) {
        if (translationKeyRepository.existsByKeyCodeAndAppTarget(keyCode, appTarget)) {
            throw new IllegalArgumentException("Bu kalit allaqachon mavjud: " + keyCode);
        }
        return translationKeyRepository.save(TranslationKey.builder()
                .keyCode(keyCode)
                .appTarget(appTarget)
                .description(description)
                .screen(screen)
                .build());
    }

    @Transactional
    public void deleteKey(Long keyId) {
        translationValueRepository.deleteAllByTranslationKeyId(keyId);
        translationKeyRepository.deleteById(keyId);
    }

    @Transactional
    public Language addLanguage(String code, String name, String flagEmoji, String baseLanguageCode, Integer sortOrder) {
        if (languageRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Bu til kodi allaqachon mavjud: " + code);
        }
        validateNoCycle(code, baseLanguageCode);
        return languageRepository.save(Language.builder()
                .code(code)
                .name(name)
                .flagEmoji(flagEmoji)
                .baseLanguageCode(baseLanguageCode)
                .active(true)
                .isDefault(false)
                .sortOrder(sortOrder)
                .build());
    }

    @Transactional
    public void editLanguage(Long id, String code, String name, String flagEmoji, String baseLanguageCode, Integer sortOrder) {
        Language language = languageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Til topilmadi: " + id));
        String oldCode = language.getCode();
        String newCode = (code == null || code.isBlank()) ? oldCode : code.trim();
        if (!newCode.equals(oldCode) && languageRepository.existsByCode(newCode)) {
            throw new IllegalArgumentException("Bu til kodi allaqachon mavjud: " + newCode);
        }
        validateNoCycle(newCode, baseLanguageCode);
        language.setCode(newCode);
        language.setName(name);
        language.setFlagEmoji(flagEmoji);
        language.setBaseLanguageCode(baseLanguageCode);
        language.setSortOrder(sortOrder);
        languageRepository.save(language);

        // TranslationValue rows reference the language by id, not code, so no cascade needed
        // there — but other languages' baseLanguageCode is a loose string reference and would
        // dangle if we didn't repoint it.
        if (!newCode.equals(oldCode)) {
            for (Language other : languageRepository.findAll()) {
                if (oldCode.equals(other.getBaseLanguageCode())) {
                    other.setBaseLanguageCode(newCode);
                    languageRepository.save(other);
                }
            }
        }
    }

    @Transactional
    public void toggleLanguageActive(Long id) {
        Language language = languageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Til topilmadi: " + id));
        language.setActive(!language.isActive());
        languageRepository.save(language);
    }

    /**
     * Bulk-upserts values for one (app, languageCode) pair from a raw JSON object.
     * Keys starting with "@" (ARB metadata, e.g. "@@locale", "@seatSelected") are skipped.
     * Unknown keys are created automatically under the given appTarget.
     */
    @Transactional
    public int importJson(AppTarget appTarget, String languageCode, Map<String, Object> raw) {
        Language language = languageRepository.findByCode(languageCode)
                .orElseThrow(() -> new IllegalArgumentException("Til topilmadi: " + languageCode));

        int count = 0;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String keyCode = entry.getKey();
            if (keyCode.startsWith("@") || entry.getValue() == null) {
                continue;
            }
            String value = String.valueOf(entry.getValue());

            TranslationKey key = translationKeyRepository.findByKeyCodeAndAppTarget(keyCode, appTarget)
                    .orElseGet(() -> translationKeyRepository.save(TranslationKey.builder()
                            .keyCode(keyCode)
                            .appTarget(appTarget)
                            .build()));

            TranslationValue tv = translationValueRepository.findByTranslationKeyIdAndLanguage_Code(key.getId(), languageCode)
                    .orElseGet(() -> TranslationValue.builder().translationKey(key).language(language).build());
            tv.setValue(value);
            translationValueRepository.save(tv);
            count++;
        }
        return count;
    }

    /**
     * Like {@link #importJson}, but never overwrites a key/language pair that already has a
     * value — it only fills in gaps (a brand-new key the app started using, or an existing key
     * that never had a value for this language). Used to keep the DB in sync with each app's
     * compiled ARB source on every startup, without ever clobbering an admin's hand-edited text.
     * Returns the number of key/language pairs actually created.
     */
    @Transactional
    public int addMissingKeys(AppTarget appTarget, String languageCode, Map<String, Object> raw) {
        Language language = languageRepository.findByCode(languageCode)
                .orElseThrow(() -> new IllegalArgumentException("Til topilmadi: " + languageCode));

        int added = 0;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String keyCode = entry.getKey();
            if (keyCode.startsWith("@") || entry.getValue() == null) {
                continue;
            }
            String value = String.valueOf(entry.getValue());

            TranslationKey key = translationKeyRepository.findByKeyCodeAndAppTarget(keyCode, appTarget)
                    .orElseGet(() -> translationKeyRepository.save(TranslationKey.builder()
                            .keyCode(keyCode)
                            .appTarget(appTarget)
                            .build()));

            if (translationValueRepository.findByTranslationKeyIdAndLanguage_Code(key.getId(), languageCode).isPresent()) {
                continue;
            }
            translationValueRepository.save(TranslationValue.builder()
                    .translationKey(key)
                    .language(language)
                    .value(value)
                    .build());
            added++;
        }
        return added;
    }

    /** Exports current values of a given (app, languageCode) as a flat key->value map. */
    @Transactional(readOnly = true)
    public Map<String, String> exportJson(AppTarget appTarget, String languageCode) {
        List<AppTarget> targets = List.of(appTarget, AppTarget.SHARED);
        List<TranslationValue> values = translationValueRepository
                .findAllByLanguage_CodeAndTranslationKey_AppTargetIn(languageCode, targets);

        Map<String, String> result = new TreeMap<>();
        for (TranslationValue v : values) {
            result.put(v.getTranslationKey().getKeyCode(), v.getValue());
        }
        return result;
    }
}
