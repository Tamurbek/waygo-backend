package com.waygo.backend;

import com.waygo.backend.entity.translation.Language;
import com.waygo.backend.entity.translation.TranslationKey;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.entity.translation.TranslationValue;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.repository.translation.TranslationKeyRepository;
import com.waygo.backend.repository.translation.TranslationValueRepository;
import com.waygo.backend.service.translation.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    private LanguageRepository languageRepository;

    @Mock
    private TranslationKeyRepository translationKeyRepository;

    @Mock
    private TranslationValueRepository translationValueRepository;

    @InjectMocks
    private TranslationService translationService;

    private Language uz;
    private Language ru;
    private Language tj;
    private TranslationKey key;

    @BeforeEach
    void setUp() {
        uz = Language.builder().id(1L).code("uz").name("O'zbekcha").active(true).build();
        ru = Language.builder().id(2L).code("ru").name("Русский").active(true).build();
        tj = Language.builder().id(3L).code("tj").name("Тоҷикӣ").baseLanguageCode("ru").active(true).build();
        key = TranslationKey.builder().id(10L).keyCode("welcome").appTarget(AppTarget.USER).build();
    }

    @Test
    void resolveForApp_fallsBackThroughBaseLanguageChain() {
        when(translationKeyRepository.findAllByAppTargetInOrderByKeyCodeAsc(List.of(AppTarget.USER, AppTarget.SHARED)))
                .thenReturn(List.of(key));
        TranslationValue ruValue = TranslationValue.builder().translationKey(key).language(ru).value("Добро пожаловать").build();
        when(translationValueRepository.findAllByTranslationKey_AppTargetIn(List.of(AppTarget.USER, AppTarget.SHARED)))
                .thenReturn(List.of(ruValue));
        when(languageRepository.findAll()).thenReturn(List.of(uz, ru, tj));

        Map<String, String> result = translationService.resolveForApp(AppTarget.USER, "tj");

        assertEquals("Добро пожаловать", result.get("welcome"));
    }

    @Test
    void resolveForApp_omitsKeyWhenNoValueAnywhereInChain() {
        when(translationKeyRepository.findAllByAppTargetInOrderByKeyCodeAsc(List.of(AppTarget.USER, AppTarget.SHARED)))
                .thenReturn(List.of(key));
        when(translationValueRepository.findAllByTranslationKey_AppTargetIn(List.of(AppTarget.USER, AppTarget.SHARED)))
                .thenReturn(List.of());
        when(languageRepository.findAll()).thenReturn(List.of(uz, ru, tj));

        Map<String, String> result = translationService.resolveForApp(AppTarget.USER, "tj");

        assertFalse(result.containsKey("welcome"));
    }

    @Test
    void resolveForApp_doesNotInfiniteLoopOnCyclicBaseLanguageChain() {
        Language a = Language.builder().id(4L).code("a").baseLanguageCode("b").active(true).build();
        Language b = Language.builder().id(5L).code("b").baseLanguageCode("a").active(true).build();
        when(translationKeyRepository.findAllByAppTargetInOrderByKeyCodeAsc(List.of(AppTarget.USER, AppTarget.SHARED)))
                .thenReturn(List.of(key));
        when(translationValueRepository.findAllByTranslationKey_AppTargetIn(List.of(AppTarget.USER, AppTarget.SHARED)))
                .thenReturn(List.of());
        when(languageRepository.findAll()).thenReturn(List.of(a, b));

        Map<String, String> result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> translationService.resolveForApp(AppTarget.USER, "a"));

        assertTrue(result.isEmpty());
    }

    @Test
    void validateNoCycle_rejectsSelfReference() {
        assertThrows(IllegalArgumentException.class, () -> translationService.validateNoCycle("uz", "uz"));
    }

    @Test
    void validateNoCycle_rejectsIndirectCycle() {
        Language a = Language.builder().code("a").baseLanguageCode("b").build();
        Language b = Language.builder().code("b").baseLanguageCode(null).build();
        when(languageRepository.findAll()).thenReturn(List.of(a, b));

        // proposing to make "b" base itself on "a" would create a -> b -> a cycle
        assertThrows(IllegalArgumentException.class, () -> translationService.validateNoCycle("b", "a"));
    }

    @Test
    void validateNoCycle_allowsValidChain() {
        when(languageRepository.findAll()).thenReturn(List.of(uz, ru));
        assertDoesNotThrow(() -> translationService.validateNoCycle("tj", "ru"));
    }

    @Test
    void importJson_skipsArbMetadataKeysAndCreatesMissingKeys() {
        when(languageRepository.findByCode("uz")).thenReturn(java.util.Optional.of(uz));
        when(translationKeyRepository.findByKeyCodeAndAppTarget("welcome", AppTarget.USER))
                .thenReturn(java.util.Optional.empty());
        when(translationKeyRepository.save(any())).thenReturn(key);
        when(translationValueRepository.findByTranslationKeyIdAndLanguage_Code(10L, "uz"))
                .thenReturn(java.util.Optional.empty());

        Map<String, Object> raw = Map.of(
                "@@locale", "uz",
                "@welcome", Map.of("description", "greeting"),
                "welcome", "Xush kelibsiz"
        );

        int count = translationService.importJson(AppTarget.USER, "uz", raw);

        assertEquals(1, count);
        verify(translationValueRepository, times(1)).save(any());
    }
}
