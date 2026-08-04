package com.waygo.backend.controller.config;

import com.waygo.backend.entity.translation.Language;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.service.translation.TranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class TranslationController {

    private final TranslationService translationService;
    private final LanguageRepository languageRepository;

    @GetMapping("/translations")
    public ResponseEntity<Map<String, String>> getTranslations(
            @RequestParam String app,
            @RequestParam String locale) {
        AppTarget appTarget = AppTarget.valueOf(app.toUpperCase());
        return ResponseEntity.ok(translationService.resolveForApp(appTarget, locale));
    }

    @GetMapping("/languages")
    public ResponseEntity<List<LanguageDto>> getLanguages() {
        List<LanguageDto> dtos = languageRepository.findAllByActiveTrueOrderBySortOrderAsc()
                .stream()
                .map(LanguageDto::from)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    public record LanguageDto(String code, String name, String flagEmoji) {
        static LanguageDto from(Language language) {
            return new LanguageDto(language.getCode(), language.getName(), language.getFlagEmoji());
        }
    }
}
