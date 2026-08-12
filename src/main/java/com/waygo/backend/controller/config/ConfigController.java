package com.waygo.backend.controller.config;

import com.waygo.backend.entity.MapSettings;
import com.waygo.backend.entity.config.*;
import com.waygo.backend.service.config.ConfigService;
import com.waygo.backend.service.MapSettingsService;
import com.waygo.backend.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;
    private final SystemSettingsService settingsService;
    private final MapSettingsService mapSettingsService;

    @GetMapping("/tariffs")
    public ResponseEntity<List<TariffPlan>> getTariffs(@RequestParam(required = false) String locale) {
        return ResponseEntity.ok(configService.getActiveTariffPlans(locale));
    }

    @GetMapping("/regions")
    public ResponseEntity<List<Region>> getRegions(@RequestParam(required = false) String locale) {
        return ResponseEntity.ok(configService.getActiveRegions(locale));
    }

    @GetMapping("/districts")
    public ResponseEntity<List<District>> getDistricts(@RequestParam(required = false) Long regionId) {
        if (regionId != null) {
            return ResponseEntity.ok(configService.getActiveDistrictsByRegion(regionId));
        }
        return ResponseEntity.ok(configService.getAllActiveDistricts());
    }

    @GetMapping("/car-brands")
    public ResponseEntity<List<CarBrand>> getCarBrands(@RequestParam(required = false) String locale) {
        return ResponseEntity.ok(configService.getActiveCarBrands(locale));
    }

    @GetMapping("/car-models")
    public ResponseEntity<List<CarModel>> getCarModels(@RequestParam(required = false) Long brandId,
                                                         @RequestParam(required = false) String locale) {
        if (brandId != null) {
            return ResponseEntity.ok(configService.getActiveCarModelsByBrand(brandId, locale));
        }
        return ResponseEntity.ok(configService.getAllActiveCarModels(locale));
    }

    @GetMapping("/car-colors")
    public ResponseEntity<List<CarColor>> getCarColors() {
        return ResponseEntity.ok(configService.getActiveCarColors());
    }

    @GetMapping("/services")
    public ResponseEntity<List<ServiceOption>> getServices(@RequestParam(required = false) String locale) {
        return ResponseEntity.ok(configService.getActiveServiceOptions(locale));
    }

    @GetMapping("/top-up-steps")
    public ResponseEntity<List<TopUpStep>> getTopUpSteps(@RequestParam(required = false) String locale) {
        return ResponseEntity.ok(configService.getTopUpSteps(locale));
    }

    /**
     * Flutter ilovalari ilovani ochganda chaqiradi.
     * appType: USER yoki DRIVER
     * Response: { "latestVersion": "1.2.0" }
     */
    @GetMapping("/app-version")
    public ResponseEntity<Map<String, String>> getAppVersion(
            @RequestParam(defaultValue = "USER") String appType) {
        String version = settingsService.getAppVersion(appType);
        return ResponseEntity.ok(Map.of("latestVersion", version));
    }

    /**
     * OTP ekranidagi "qaytadan yuborish" tugmasi qancha soniyadan keyin
     * faollashishi kerakligini qaytaradi — admin panelda /admin/settings
     * orqali sozlanadi.
     */
    @GetMapping("/otp-settings")
    public ResponseEntity<Map<String, Integer>> getOtpSettings() {
        int resendSeconds = settingsService.getSettings().getOtpResendSeconds();
        return ResponseEntity.ok(Map.of("resendSeconds", resendSeconds));
    }

    /**
     * Flutter ilovalari (waygo_user, waygo_driver) ilova ochilganda chaqiradi.
     * Yandex Map kamera/zoom, road-snap, look-ahead, vizual va interval sozlamalarini qaytaradi —
     * admin panelda /admin/map-settings orqali tahrirlanadi.
     */
    @GetMapping("/map-settings")
    public ResponseEntity<MapSettings> getMapSettings() {
        return ResponseEntity.ok(mapSettingsService.getSettings());
    }

    /**
     * Bulk import: viloyat va tumanlarni JSON dan bazaga yuklash.
     * Mavjud barcha regions/districts o'chiriladi va yangilari saqlanadi.
     * Faqat server tomonidan ishlatilishi kerak (masalan, curl orqali).
     */
    @PostMapping("/locations/import")
    public ResponseEntity<Map<String, Object>> importLocations(
            @RequestBody List<Map<String, Object>> regionsData) {
        try {
            Map<String, Integer> result = configService.importLocations(regionsData);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Ma'lumotlar muvaffaqiyatli yuklandi",
                "regions", result.get("regions"),
                "districts", result.get("districts")
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "Xatolik: " + e.getMessage()
            ));
        }
    }
}
