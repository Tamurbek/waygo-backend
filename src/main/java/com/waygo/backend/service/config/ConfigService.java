package com.waygo.backend.service.config;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.repository.config.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
    
    // Admin CRUD methods can be added here later
}
