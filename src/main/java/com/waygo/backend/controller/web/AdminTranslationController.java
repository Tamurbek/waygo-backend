package com.waygo.backend.controller.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waygo.backend.entity.translation.Language;
import com.waygo.backend.entity.translation.TranslationKey;
import com.waygo.backend.entity.translation.TranslationKey.AppTarget;
import com.waygo.backend.entity.translation.TranslationValue;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.repository.translation.TranslationKeyRepository;
import com.waygo.backend.repository.translation.TranslationValueRepository;
import com.waygo.backend.service.translation.TranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
@RequestMapping("/admin/config/translations")
@RequiredArgsConstructor
public class AdminTranslationController {

    private final TranslationService translationService;
    private final LanguageRepository languageRepository;
    private final TranslationKeyRepository translationKeyRepository;
    private final TranslationValueRepository translationValueRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping
    public String index(Model model, @RequestParam(defaultValue = "user") String app) {
        AppTarget appTarget = parseAppTarget(app);

        List<Language> languages = languageRepository.findAllByOrderBySortOrderAsc();
        List<TranslationKey> keys = translationKeyRepository.findAllByAppTargetInOrderByKeyCodeAsc(List.of(appTarget));
        List<TranslationValue> values = translationValueRepository.findAllByTranslationKey_AppTargetIn(List.of(appTarget));

        Map<Long, Map<String, String>> byKeyId = new HashMap<>();
        for (TranslationValue v : values) {
            byKeyId.computeIfAbsent(v.getTranslationKey().getId(), k -> new HashMap<>())
                   .put(v.getLanguage().getCode(), v.getValue());
        }

        List<TranslationRowView> rows = new ArrayList<>();
        for (TranslationKey key : keys) {
            rows.add(new TranslationRowView(
                    key.getId(), key.getKeyCode(), key.getDescription(),
                    byKeyId.getOrDefault(key.getId(), Map.of())));
        }

        model.addAttribute("title", "Tarjimalar");
        model.addAttribute("activeItem", "config_translations");
        model.addAttribute("app", app.toLowerCase());
        model.addAttribute("languages", languages);
        model.addAttribute("rows", rows);
        return "admin/config/translations";
    }

    @PostMapping("/cell")
    @ResponseBody
    public ResponseEntity<String> saveCell(@RequestParam Long keyId, @RequestParam String langCode, @RequestParam(required = false, defaultValue = "") String value) {
        try {
            translationService.upsertValue(keyId, langCode, value);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/keys/add")
    public String addKey(@RequestParam String keyCode, @RequestParam String appTarget,
                          @RequestParam(required = false) String description, @RequestParam(required = false) String screen) {
        AppTarget target = parseAppTarget(appTarget);
        try {
            translationService.addKey(keyCode.trim(), target, description, screen);
            return "redirect:/admin/config/translations?app=" + target.name().toLowerCase() + "&success";
        } catch (IllegalArgumentException e) {
            return "redirect:/admin/config/translations?app=" + target.name().toLowerCase() + "&error=" + encode(e.getMessage());
        }
    }

    @PostMapping("/keys/delete/{id}")
    public String deleteKey(@PathVariable Long id) {
        String app = translationKeyRepository.findById(id)
                .map(k -> k.getAppTarget().name().toLowerCase())
                .orElse("user");
        translationService.deleteKey(id);
        return "redirect:/admin/config/translations?app=" + app + "&deleted";
    }

    @PostMapping("/languages/add")
    public String addLanguage(@RequestParam String code, @RequestParam String name,
                               @RequestParam(required = false) String flagEmoji,
                               @RequestParam(required = false) String baseLanguageCode,
                               @RequestParam(required = false) String sortOrder,
                               @RequestParam(defaultValue = "user") String app) {
        try {
            translationService.addLanguage(code.trim(), name.trim(), flagEmoji, emptyToNull(baseLanguageCode), parseIntOrNull(sortOrder));
            return "redirect:/admin/config/translations?app=" + app + "&success";
        } catch (IllegalArgumentException e) {
            return "redirect:/admin/config/translations?app=" + app + "&error=" + encode(e.getMessage());
        }
    }

    @PostMapping("/languages/edit/{id}")
    public String editLanguage(@PathVariable Long id, @RequestParam(required = false) String code,
                                @RequestParam String name,
                                @RequestParam(required = false) String flagEmoji,
                                @RequestParam(required = false) String baseLanguageCode,
                                @RequestParam(required = false) String sortOrder,
                                @RequestParam(defaultValue = "user") String app) {
        try {
            translationService.editLanguage(id, emptyToNull(code), name.trim(), flagEmoji, emptyToNull(baseLanguageCode), parseIntOrNull(sortOrder));
            return "redirect:/admin/config/translations?app=" + app + "&updated";
        } catch (IllegalArgumentException e) {
            return "redirect:/admin/config/translations?app=" + app + "&error=" + encode(e.getMessage());
        }
    }

    @PostMapping("/languages/toggle-active/{id}")
    @ResponseBody
    public String toggleLanguageActive(@PathVariable Long id) {
        translationService.toggleLanguageActive(id);
        return "ok";
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String app, @RequestParam String lang) throws Exception {
        AppTarget appTarget = parseAppTarget(app);
        Map<String, String> data = translationService.exportJson(appTarget, lang);
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);

        String filename = app.toLowerCase() + "_" + lang + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    /**
     * For starting a brand-new language: unlike {@link #export}, which only contains values
     * already stored for that exact language (empty for a language nobody has translated yet),
     * this returns every key for the app resolved through sourceLang's own fallback chain — i.e.
     * the full key set with real text to translate offline, then re-upload via {@link #importJson}
     * under the new language's code.
     */
    @GetMapping("/export-template")
    public ResponseEntity<byte[]> exportTemplate(@RequestParam String app, @RequestParam(defaultValue = "uz") String sourceLang) throws Exception {
        AppTarget appTarget = parseAppTarget(app);
        Map<String, String> data = translationService.resolveForApp(appTarget, sourceLang);
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);

        String filename = app.toLowerCase() + "_" + sourceLang + "_shablon.json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/import")
    public String importJson(@RequestParam String app, @RequestParam String lang, @RequestParam String jsonText) {
        AppTarget appTarget = parseAppTarget(app);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(jsonText, Map.class);
            int count = translationService.importJson(appTarget, lang, raw);
            return "redirect:/admin/config/translations?app=" + app.toLowerCase() + "&imported=" + count;
        } catch (Exception e) {
            return "redirect:/admin/config/translations?app=" + app.toLowerCase() + "&error=" + encode("JSON o'qib bo'lmadi: " + e.getMessage());
        }
    }

    private AppTarget parseAppTarget(String value) {
        try {
            return AppTarget.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AppTarget.USER;
        }
    }

    private String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public record TranslationRowView(Long keyId, String keyCode, String description, Map<String, String> valuesByLang) {}
}
