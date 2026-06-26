package com.waygo.backend.controller.config;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.service.config.ConfigService;
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

    @GetMapping("/tariffs")
    public ResponseEntity<List<TariffPlan>> getTariffs() {
        return ResponseEntity.ok(configService.getActiveTariffPlans());
    }

    @GetMapping("/regions")
    public ResponseEntity<List<Region>> getRegions() {
        return ResponseEntity.ok(configService.getActiveRegions());
    }

    @GetMapping("/districts")
    public ResponseEntity<List<District>> getDistricts(@RequestParam(required = false) Long regionId) {
        if (regionId != null) {
            return ResponseEntity.ok(configService.getActiveDistrictsByRegion(regionId));
        }
        return ResponseEntity.ok(configService.getAllActiveDistricts());
    }

    @GetMapping("/car-brands")
    public ResponseEntity<List<CarBrand>> getCarBrands() {
        return ResponseEntity.ok(configService.getActiveCarBrands());
    }

    @GetMapping("/car-models")
    public ResponseEntity<List<CarModel>> getCarModels(@RequestParam(required = false) Long brandId) {
        if (brandId != null) {
            return ResponseEntity.ok(configService.getActiveCarModelsByBrand(brandId));
        }
        return ResponseEntity.ok(configService.getAllActiveCarModels());
    }

    @GetMapping("/car-colors")
    public ResponseEntity<List<CarColor>> getCarColors() {
        return ResponseEntity.ok(configService.getActiveCarColors());
    }

    @GetMapping("/services")
    public ResponseEntity<List<ServiceOption>> getServices() {
        return ResponseEntity.ok(configService.getActiveServiceOptions());
    }

    @GetMapping("/top-up-steps")
    public ResponseEntity<List<TopUpStep>> getTopUpSteps() {
        return ResponseEntity.ok(configService.getTopUpSteps());
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
