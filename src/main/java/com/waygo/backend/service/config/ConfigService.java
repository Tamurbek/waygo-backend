package com.waygo.backend.service.config;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.repository.config.*;
import com.waygo.backend.service.translation.EntityTranslationService;
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
    private final EntityTranslationService entityTranslationService;

    public List<TopUpStep> getTopUpSteps(String locale) {
        List<TopUpStep> steps = topUpStepRepository.findAllByOrderByStepNumberAsc();
        for (TopUpStep step : steps) {
            step.setTitle(entityTranslationService.resolve(step.getTitleKeyId(), locale, step.getTitle()));
            step.setDescription(entityTranslationService.resolve(step.getDescriptionKeyId(), locale, step.getDescription()));
        }
        return steps;
    }

    public List<TariffPlan> getActiveTariffPlans(String locale) {
        List<TariffPlan> plans = tariffPlanRepository.findAllByIsActiveTrue();
        for (TariffPlan plan : plans) {
            plan.setDuration(entityTranslationService.resolve(plan.getDurationKeyId(), locale, plan.getDuration()));
            plan.setDescription(entityTranslationService.resolve(plan.getDescriptionKeyId(), locale, plan.getDescription()));
            resolveFeatures(plan, locale);
        }
        return plans;
    }

    private void resolveFeatures(TariffPlan plan, String locale) {
        List<String> features = plan.getFeatures();
        List<Long> keyIds = plan.getFeatureKeyIds();
        if (features == null || keyIds == null || locale == null || locale.isBlank()) {
            return;
        }
        for (int i = 0; i < features.size() && i < keyIds.size(); i++) {
            features.set(i, entityTranslationService.resolve(keyIds.get(i), locale, features.get(i)));
        }
    }

    public List<Region> getActiveRegions(String locale) {
        List<Region> regions = regionRepository.findAllByIsActiveTrue();
        for (Region region : regions) {
            region.setName(entityTranslationService.resolve(region.getNameKeyId(), locale, region.getName()));
        }
        return regions;
    }

    public List<District> getActiveDistrictsByRegion(Long regionId, String locale) {
        List<District> districts = districtRepository.findAllByRegionIdAndIsActiveTrue(regionId);
        for (District district : districts) {
            district.setName(entityTranslationService.resolve(district.getNameKeyId(), locale, district.getName()));
        }
        return districts;
    }

    public List<District> getAllActiveDistricts(String locale) {
        List<District> districts = districtRepository.findAllByIsActiveTrue();
        for (District district : districts) {
            district.setName(entityTranslationService.resolve(district.getNameKeyId(), locale, district.getName()));
        }
        return districts;
    }

    public List<CarBrand> getActiveCarBrands(String locale) {
        List<CarBrand> brands = carBrandRepository.findAllByIsActiveTrue();
        for (CarBrand brand : brands) {
            brand.setName(entityTranslationService.resolve(brand.getNameKeyId(), locale, brand.getName()));
            if (brand.getModels() != null) {
                for (CarModel model : brand.getModels()) {
                    model.setName(entityTranslationService.resolve(model.getNameKeyId(), locale, model.getName()));
                }
            }
        }
        return brands;
    }

    public List<CarModel> getActiveCarModelsByBrand(Long brandId, String locale) {
        List<CarModel> models = carModelRepository.findAllByBrandIdAndIsActiveTrue(brandId);
        for (CarModel model : models) {
            model.setName(entityTranslationService.resolve(model.getNameKeyId(), locale, model.getName()));
        }
        return models;
    }

    public List<CarModel> getAllActiveCarModels(String locale) {
        List<CarModel> models = carModelRepository.findAllByIsActiveTrue();
        for (CarModel model : models) {
            model.setName(entityTranslationService.resolve(model.getNameKeyId(), locale, model.getName()));
        }
        return models;
    }

    public List<CarColor> getActiveCarColors() {
        return carColorRepository.findAllByIsActiveTrue();
    }

    public List<ServiceOption> getActiveServiceOptions(String locale) {
        List<ServiceOption> options = serviceOptionRepository.findAllByIsActiveTrue();
        for (ServiceOption option : options) {
            option.setName(entityTranslationService.resolve(option.getNameKeyId(), locale, option.getName()));
        }
        return options;
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
