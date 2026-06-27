package com.waygo.backend.service.config;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.repository.config.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConfigService {
    
    private final TariffPlanRepository tariffPlanRepository;
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final CarBrandRepository carBrandRepository;
    private final CarModelRepository carModelRepository;
    private final CarColorRepository carColorRepository;
    private final ServiceOptionRepository serviceOptionRepository;
    private final TopUpStepRepository topUpStepRepository;

    public List<TopUpStep> getTopUpSteps() {
        return topUpStepRepository.findAllByOrderByStepNumberAsc();
    }

    public List<TariffPlan> getActiveTariffPlans() {
        return tariffPlanRepository.findAllByIsActiveTrue();
    }

    public List<Region> getActiveRegions() {
        return regionRepository.findAllByIsActiveTrue();
    }

    public List<District> getActiveDistrictsByRegion(Long regionId) {
        return districtRepository.findAllByRegionIdAndIsActiveTrue(regionId);
    }
    
    public List<District> getAllActiveDistricts() {
        return districtRepository.findAllByIsActiveTrue();
    }

    public List<CarBrand> getActiveCarBrands() {
        return carBrandRepository.findAllByIsActiveTrue();
    }

    public List<CarModel> getActiveCarModelsByBrand(Long brandId) {
        return carModelRepository.findAllByBrandIdAndIsActiveTrue(brandId);
    }

    public List<CarModel> getAllActiveCarModels() {
        return carModelRepository.findAllByIsActiveTrue();
    }

    public List<CarColor> getActiveCarColors() {
        return carColorRepository.findAllByIsActiveTrue();
    }
    
    public List<ServiceOption> getActiveServiceOptions() {
        return serviceOptionRepository.findAllByIsActiveTrue();
    }

    @Transactional
    public Map<String, Integer> importLocations(List<Map<String, Object>> regionsData) {
        districtRepository.deleteAll();
        regionRepository.deleteAll();

        int regionCount = 0;
        int districtCount = 0;

        for (Map<String, Object> regionData : regionsData) {
            String regionName = (String) regionData.get("name_uz");
            if (regionName == null || regionName.isBlank()) continue;

            Double regionLat = toDouble(regionData.get("lat"));
            Double regionLon = toDouble(regionData.get("lon"));

            Region region = regionRepository.save(
                Region.builder()
                    .name(regionName)
                    .latitude(regionLat)
                    .longitude(regionLon)
                    .isActive(true)
                    .build()
            );
            regionCount++;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> districts = (List<Map<String, Object>>) regionData.get("districts");
            if (districts != null) {
                for (Map<String, Object> districtData : districts) {
                    String districtName = (String) districtData.get("name_uz");
                    if (districtName == null || districtName.isBlank()) continue;

                    Double distLat = toDouble(districtData.get("lat"));
                    Double distLon = toDouble(districtData.get("lon"));

                    districtRepository.save(
                        District.builder()
                            .name(districtName)
                            .latitude(distLat)
                            .longitude(distLon)
                            .region(region)
                            .isActive(true)
                            .build()
                    );
                    districtCount++;
                }
            }
        }

        return Map.of("regions", regionCount, "districts", districtCount);
    }

    /** JSON raqamini Double ga xavfsiz o'tkazadi (Integer ham, Double ham bo'lishi mumkin) */
    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Double d) return d;
        if (value instanceof Integer i) return i.doubleValue();
        if (value instanceof Long l) return l.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
