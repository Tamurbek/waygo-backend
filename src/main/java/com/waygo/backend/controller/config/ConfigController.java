package com.waygo.backend.controller.config;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.service.config.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

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
}
