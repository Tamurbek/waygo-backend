package com.waygo.backend.controller.web;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.repository.config.*;
import com.waygo.backend.repository.translation.LanguageRepository;
import com.waygo.backend.service.translation.EntityTranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final TariffPlanRepository tariffPlanRepository;
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final CarBrandRepository carBrandRepository;
    private final CarModelRepository carModelRepository;
    private final CarColorRepository carColorRepository;
    private final ServiceOptionRepository serviceOptionRepository;
    private final TopUpStepRepository topUpStepRepository;
    private final com.waygo.backend.service.FileService fileService;
    private final LanguageRepository languageRepository;
    private final EntityTranslationService entityTranslationService;

    /**
     * Upserts one TranslationKey per feature row, aligned by index with {@code features}.
     * Rows beyond the new list's length (removed by the admin) have their key+values deleted.
     */
    private List<Long> upsertFeatureKeys(Long tariffId, List<Long> existingKeyIds, List<String> features,
                                          List<Map<String, String>> featureTranslations) {
        List<Long> result = new ArrayList<>();
        if (features == null) {
            return result;
        }
        for (int i = 0; i < features.size(); i++) {
            Long existingKeyId = (existingKeyIds != null && i < existingKeyIds.size()) ? existingKeyIds.get(i) : null;
            Map<String, String> values = (featureTranslations != null && i < featureTranslations.size())
                    ? featureTranslations.get(i) : null;
            result.add(entityTranslationService.upsertField(existingKeyId,
                    "cfg.tariff_plan.feature." + tariffId + "." + i, values));
        }
        if (existingKeyIds != null) {
            for (int i = features.size(); i < existingKeyIds.size(); i++) {
                entityTranslationService.deleteField(existingKeyIds.get(i));
            }
        }
        return result;
    }

    @GetMapping("/tariffs")
    public String tariffs(Model model, @RequestParam(required = false) Long edit) {
        model.addAttribute("title", "Tarif Rejalari");
        model.addAttribute("tariffs", tariffPlanRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
        model.addAttribute("activeItem", "config_tariffs");
        model.addAttribute("activeLanguages", languageRepository.findAllByActiveTrueOrderBySortOrderAsc());
        if (edit != null) {
            tariffPlanRepository.findById(edit).ifPresent(tariff -> {
                model.addAttribute("editItem", tariff);
                model.addAttribute("editDurationTranslations", entityTranslationService.valuesFor(tariff.getDurationKeyId()));
                model.addAttribute("editDescriptionTranslations", entityTranslationService.valuesFor(tariff.getDescriptionKeyId()));
                List<Map<String, String>> featureTranslations = new ArrayList<>();
                if (tariff.getFeatureKeyIds() != null) {
                    for (Long keyId : tariff.getFeatureKeyIds()) {
                        featureTranslations.add(entityTranslationService.valuesFor(keyId));
                    }
                }
                model.addAttribute("editFeatureTranslations", featureTranslations);
            });
        }
        return "admin/config/tariffs";
    }

    @PostMapping("/tariffs/add")
    public String addTariff(@ModelAttribute TariffPlan tariffPlan) {
        tariffPlan.setVip(false);
        TariffPlan saved = tariffPlanRepository.save(tariffPlan);
        saved.setDurationKeyId(entityTranslationService.upsertField(null,
                "cfg.tariff_plan.duration." + saved.getId(), tariffPlan.getDurationTranslations()));
        saved.setDescriptionKeyId(entityTranslationService.upsertField(null,
                "cfg.tariff_plan.description." + saved.getId(), tariffPlan.getDescriptionTranslations()));
        saved.setFeatureKeyIds(upsertFeatureKeys(saved.getId(), null, tariffPlan.getFeatures(), tariffPlan.getFeatureTranslations()));
        tariffPlanRepository.save(saved);
        return "redirect:/admin/config/tariffs?success";
    }

    @PostMapping("/tariffs/edit/{id}")
    public String editTariff(@PathVariable Long id, @ModelAttribute TariffPlan form) {
        tariffPlanRepository.findById(id).ifPresent(existing -> {
            existing.setDuration(form.getDuration());
            existing.setDurationDays(form.getDurationDays());
            existing.setPrice(form.getPrice());
            existing.setOldPrice(form.getOldPrice());
            existing.setDescription(form.getDescription());
            existing.setPopular(form.isPopular());
            existing.setActive(form.isActive());
            if (form.getFeatures() != null) existing.setFeatures(form.getFeatures());
            existing.setDurationKeyId(entityTranslationService.upsertField(existing.getDurationKeyId(),
                    "cfg.tariff_plan.duration." + id, form.getDurationTranslations()));
            existing.setDescriptionKeyId(entityTranslationService.upsertField(existing.getDescriptionKeyId(),
                    "cfg.tariff_plan.description." + id, form.getDescriptionTranslations()));
            existing.setFeatureKeyIds(upsertFeatureKeys(id, existing.getFeatureKeyIds(), form.getFeatures(), form.getFeatureTranslations()));
            tariffPlanRepository.save(existing);
        });
        return "redirect:/admin/config/tariffs?updated";
    }

    @PostMapping("/tariffs/toggle-active/{id}")
    @ResponseBody
    public String toggleTariffActive(@PathVariable Long id) {
        tariffPlanRepository.findById(id).ifPresent(tariff -> {
            tariff.setActive(!tariff.isActive());
            tariffPlanRepository.save(tariff);
        });
        return "ok";
    }

    @PostMapping("/tariffs/delete/{id}")
    public String deleteTariff(@PathVariable Long id) {
        tariffPlanRepository.deleteById(id);
        return "redirect:/admin/config/tariffs?deleted";
    }

    @Transactional(readOnly = true)
    @GetMapping("/regions")
    public String regions(Model model, @RequestParam(required = false) Long editRegion, @RequestParam(required = false) Long editDistrict) {
        model.addAttribute("title", "Viloyatlar va Tumanlar");
        List<Region> regions = regionRepository.findAllWithDistricts();
        model.addAttribute("regions", regions);
        model.addAttribute("activeItem", "config_regions");
        model.addAttribute("activeLanguages", languageRepository.findAllByActiveTrueOrderBySortOrderAsc());
        Map<Long, Map<String, String>> regionTranslations = new java.util.HashMap<>();
        for (Region r : regions) {
            regionTranslations.put(r.getId(), entityTranslationService.valuesFor(r.getNameKeyId()));
        }
        model.addAttribute("regionTranslations", regionTranslations);
        if (editRegion != null) {
            regionRepository.findById(editRegion).ifPresent(region -> {
                model.addAttribute("editRegion", region);
                model.addAttribute("editRegionTranslations", entityTranslationService.valuesFor(region.getNameKeyId()));
            });
        }
        if (editDistrict != null) {
            districtRepository.findById(editDistrict).ifPresent(district -> model.addAttribute("editDistrict", district));
        }
        return "admin/config/regions";
    }

    @PostMapping("/regions/add")
    public String addRegion(@ModelAttribute Region region) {
        region.setActive(true);
        Region saved = regionRepository.save(region);
        saved.setNameKeyId(entityTranslationService.upsertField(null,
                "cfg.region.name." + saved.getId(), region.getNameTranslations()));
        regionRepository.save(saved);
        return "redirect:/admin/config/regions?success";
    }

    @PostMapping("/regions/edit/{id}")
    public String editRegion(@PathVariable Long id, @ModelAttribute Region region) {
        regionRepository.findById(id).ifPresent(existing -> {
            existing.setName(region.getName());
            existing.setActive(region.isActive());
            existing.setLatitude(region.getLatitude());
            existing.setLongitude(region.getLongitude());
            existing.setNameKeyId(entityTranslationService.upsertField(existing.getNameKeyId(),
                    "cfg.region.name." + id, region.getNameTranslations()));
            regionRepository.save(existing);
        });
        return "redirect:/admin/config/regions?updated";
    }

    @PostMapping("/regions/toggle-active/{id}")
    @ResponseBody
    public String toggleRegionActive(@PathVariable Long id) {
        regionRepository.findById(id).ifPresent(region -> {
            region.setActive(!region.isActive());
            regionRepository.save(region);
        });
        return "ok";
    }

    @PostMapping("/districts/add")
    public String addDistrict(@ModelAttribute District district, @RequestParam Long regionId) {
        Region region = regionRepository.findById(regionId).orElseThrow();
        district.setRegion(region);
        district.setActive(true);
        districtRepository.save(district);
        return "redirect:/admin/config/regions?success";
    }

    @PostMapping("/districts/edit/{id}")
    public String editDistrict(@PathVariable Long id, @ModelAttribute District district, @RequestParam Long regionId) {
        districtRepository.findById(id).ifPresent(existing -> {
            existing.setName(district.getName());
            Region region = regionRepository.findById(regionId).orElseThrow();
            existing.setRegion(region);
            existing.setActive(district.isActive());
            existing.setLatitude(district.getLatitude());
            existing.setLongitude(district.getLongitude());
            districtRepository.save(existing);
        });
        return "redirect:/admin/config/regions?updated";
    }

    @PostMapping("/districts/toggle-active/{id}")
    @ResponseBody
    public String toggleDistrictActive(@PathVariable Long id) {
        districtRepository.findById(id).ifPresent(district -> {
            district.setActive(!district.isActive());
            districtRepository.save(district);
        });
        return "ok";
    }

    @Transactional(readOnly = true)
    @GetMapping("/cars")
    public String cars(Model model, @RequestParam(required = false) Long editBrand, @RequestParam(required = false) Long editModel, @RequestParam(required = false) Long editColor) {
        model.addAttribute("title", "Mashina turlari");
        List<CarBrand> brands = carBrandRepository.findAllWithModels();
        model.addAttribute("brands", brands);
        model.addAttribute("colors", carColorRepository.findAll());
        model.addAttribute("activeItem", "config_cars");
        model.addAttribute("activeLanguages", languageRepository.findAllByActiveTrueOrderBySortOrderAsc());
        Map<Long, Map<String, String>> brandTranslations = new java.util.HashMap<>();
        Map<Long, Map<String, String>> modelTranslations = new java.util.HashMap<>();
        for (CarBrand b : brands) {
            brandTranslations.put(b.getId(), entityTranslationService.valuesFor(b.getNameKeyId()));
            for (CarModel m : b.getModels()) {
                modelTranslations.put(m.getId(), entityTranslationService.valuesFor(m.getNameKeyId()));
            }
        }
        model.addAttribute("brandTranslations", brandTranslations);
        model.addAttribute("modelTranslations", modelTranslations);

        if (editBrand != null) {
            carBrandRepository.findById(editBrand).ifPresent(brand -> {
                model.addAttribute("editBrand", brand);
                model.addAttribute("editBrandTranslations", entityTranslationService.valuesFor(brand.getNameKeyId()));
            });
        }
        if (editModel != null) {
            carModelRepository.findById(editModel).ifPresent(carModel -> {
                model.addAttribute("editModel", carModel);
                model.addAttribute("editModelTranslations", entityTranslationService.valuesFor(carModel.getNameKeyId()));
            });
        }
        if (editColor != null) {
            carColorRepository.findById(editColor).ifPresent(color -> model.addAttribute("editColor", color));
        }
        return "admin/config/cars";
    }

    @PostMapping("/cars/brands/add")
    public String addCarBrand(@ModelAttribute CarBrand brand) {
        CarBrand saved = carBrandRepository.save(brand);
        saved.setNameKeyId(entityTranslationService.upsertField(null,
                "cfg.car_brand.name." + saved.getId(), brand.getNameTranslations()));
        carBrandRepository.save(saved);
        return "redirect:/admin/config/cars?success";
    }

    @PostMapping("/cars/brands/edit/{id}")
    public String editCarBrand(@PathVariable Long id, @ModelAttribute CarBrand brand) {
        carBrandRepository.findById(id).ifPresent(existing -> {
            existing.setName(brand.getName());
            existing.setActive(brand.isActive());
            existing.setNameKeyId(entityTranslationService.upsertField(existing.getNameKeyId(),
                    "cfg.car_brand.name." + id, brand.getNameTranslations()));
            carBrandRepository.save(existing);
        });
        return "redirect:/admin/config/cars?updated";
    }

    @PostMapping("/cars/models/add")
    public String addCarModel(@ModelAttribute CarModel carModel, @RequestParam Long brandId) {
        CarBrand brand = carBrandRepository.findById(brandId).orElseThrow();
        carModel.setBrand(brand);
        CarModel saved = carModelRepository.save(carModel);
        saved.setNameKeyId(entityTranslationService.upsertField(null,
                "cfg.car_model.name." + saved.getId(), carModel.getNameTranslations()));
        carModelRepository.save(saved);
        return "redirect:/admin/config/cars?success";
    }

    @PostMapping("/cars/models/edit/{id}")
    public String editCarModel(@PathVariable Long id, @ModelAttribute CarModel carModel, @RequestParam Long brandId) {
        carModelRepository.findById(id).ifPresent(existing -> {
            CarBrand brand = carBrandRepository.findById(brandId).orElseThrow();
            existing.setName(carModel.getName());
            existing.setBrand(brand);
            existing.setActive(carModel.isActive());
            existing.setNameKeyId(entityTranslationService.upsertField(existing.getNameKeyId(),
                    "cfg.car_model.name." + id, carModel.getNameTranslations()));
            carModelRepository.save(existing);
        });
        return "redirect:/admin/config/cars?updated";
    }

    @PostMapping("/cars/colors/add")
    public String addCarColor(@ModelAttribute CarColor color,
                              @RequestParam(value = "imageFile", required = false) org.springframework.web.multipart.MultipartFile imageFile) {
        if (imageFile != null && !imageFile.isEmpty()) {
            try {
                String fileName = fileService.saveFile(imageFile);
                color.setImageUrl("/uploads/" + fileName);
            } catch (java.io.IOException e) {
                // Log and ignore
            }
        }
        carColorRepository.save(color);
        return "redirect:/admin/config/cars?success";
    }

    @PostMapping("/cars/colors/edit/{id}")
    public String editCarColor(@PathVariable Long id, @ModelAttribute CarColor color,
                               @RequestParam(value = "imageFile", required = false) org.springframework.web.multipart.MultipartFile imageFile) {
        carColorRepository.findById(id).ifPresent(existing -> {
            existing.setName(color.getName());
            existing.setHexCode(color.getHexCode());
            if (imageFile != null && !imageFile.isEmpty()) {
                try {
                    String fileName = fileService.saveFile(imageFile);
                    existing.setImageUrl("/uploads/" + fileName);
                } catch (java.io.IOException e) {
                    // Log
                }
            }
            carColorRepository.save(existing);
        });
        return "redirect:/admin/config/cars?updated";
    }

    @GetMapping("/services")
    public String services(Model model, @RequestParam(required = false) Long edit) {
        model.addAttribute("title", "Xizmatlar va Qulayliklar");
        List<ServiceOption> services = serviceOptionRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        model.addAttribute("services", services);
        model.addAttribute("activeItem", "config_services");
        model.addAttribute("activeLanguages", languageRepository.findAllByActiveTrueOrderBySortOrderAsc());
        Map<Long, Map<String, String>> serviceTranslations = new java.util.HashMap<>();
        for (ServiceOption s : services) {
            serviceTranslations.put(s.getId(), entityTranslationService.valuesFor(s.getNameKeyId()));
        }
        model.addAttribute("serviceTranslations", serviceTranslations);
        if (edit != null) {
            serviceOptionRepository.findById(edit).ifPresent(service -> {
                model.addAttribute("editItem", service);
                model.addAttribute("editItemTranslations", entityTranslationService.valuesFor(service.getNameKeyId()));
            });
        }
        return "admin/config/services";
    }

    @PostMapping("/services/add")
    public String addService(@ModelAttribute ServiceOption serviceOption,
                             @RequestParam(name = "active", required = false) String activeParam) {
        serviceOption.setActive("true".equals(activeParam));
        ServiceOption saved = serviceOptionRepository.save(serviceOption);
        saved.setNameKeyId(entityTranslationService.upsertField(null,
                "cfg.service_option.name." + saved.getId(), serviceOption.getNameTranslations()));
        serviceOptionRepository.save(saved);
        return "redirect:/admin/config/services?success";
    }

    @PostMapping("/services/edit/{id}")
    public String editService(@PathVariable Long id, @ModelAttribute ServiceOption form,
                              @RequestParam(name = "active", required = false) String activeParam) {
        serviceOptionRepository.findById(id).ifPresent(existing -> {
            existing.setName(form.getName());
            existing.setIconKey(form.getIconKey());
            existing.setType(form.getType());
            existing.setActive("true".equals(activeParam));
            existing.setNameKeyId(entityTranslationService.upsertField(existing.getNameKeyId(),
                    "cfg.service_option.name." + id, form.getNameTranslations()));
            serviceOptionRepository.save(existing);
        });
        return "redirect:/admin/config/services?updated";
    }

    @PostMapping("/services/toggle-active/{id}")
    @ResponseBody
    public String toggleServiceActive(@PathVariable Long id) {
        serviceOptionRepository.findById(id).ifPresent(service -> {
            service.setActive(!service.isActive());
            serviceOptionRepository.save(service);
        });
        return "ok";
    }

    @PostMapping("/services/delete/{id}")
    public String deleteService(@PathVariable Long id) {
        serviceOptionRepository.deleteById(id);
        return "redirect:/admin/config/services?deleted";
    }

    @GetMapping("/top-up-steps")
    public String topUpSteps(Model model, @RequestParam(required = false) Long edit) {
        model.addAttribute("title", "Balans To'ldirish Qo'llanmasi");
        model.addAttribute("steps", topUpStepRepository.findAll(Sort.by(Sort.Direction.ASC, "stepNumber")));
        model.addAttribute("activeItem", "config_top_up_steps");
        model.addAttribute("activeLanguages", languageRepository.findAllByActiveTrueOrderBySortOrderAsc());
        if (edit != null) {
            topUpStepRepository.findById(edit).ifPresent(step -> {
                model.addAttribute("editItem", step);
                model.addAttribute("editTitleTranslations", entityTranslationService.valuesFor(step.getTitleKeyId()));
                model.addAttribute("editDescriptionTranslations", entityTranslationService.valuesFor(step.getDescriptionKeyId()));
            });
        }
        return "admin/config/top-up-steps";
    }

    @PostMapping("/top-up-steps/add")
    public String addTopUpStep(
            @ModelAttribute TopUpStep topUpStep,
            @RequestParam(value = "imageFile", required = false) org.springframework.web.multipart.MultipartFile imageFile) {
        if (imageFile != null && !imageFile.isEmpty()) {
            try {
                String fileName = fileService.saveFile(imageFile);
                topUpStep.setImageUrl("/uploads/" + fileName);
            } catch (java.io.IOException e) {
                // Log and ignore
            }
        }
        TopUpStep saved = topUpStepRepository.save(topUpStep);
        saved.setTitleKeyId(entityTranslationService.upsertField(null,
                "cfg.top_up_step.title." + saved.getId(), topUpStep.getTitleTranslations()));
        saved.setDescriptionKeyId(entityTranslationService.upsertField(null,
                "cfg.top_up_step.description." + saved.getId(), topUpStep.getDescriptionTranslations()));
        topUpStepRepository.save(saved);
        return "redirect:/admin/config/top-up-steps?success";
    }

    @PostMapping("/top-up-steps/edit/{id}")
    public String editTopUpStep(
            @PathVariable Long id,
            @ModelAttribute TopUpStep form,
            @RequestParam(value = "imageFile", required = false) org.springframework.web.multipart.MultipartFile imageFile) {
        topUpStepRepository.findById(id).ifPresent(existing -> {
            existing.setStepNumber(form.getStepNumber());
            existing.setTitle(form.getTitle());
            existing.setDescription(form.getDescription());
            if (imageFile != null && !imageFile.isEmpty()) {
                try {
                    String fileName = fileService.saveFile(imageFile);
                    existing.setImageUrl("/uploads/" + fileName);
                } catch (java.io.IOException e) {
                    // Log
                }
            }
            existing.setTitleKeyId(entityTranslationService.upsertField(existing.getTitleKeyId(),
                    "cfg.top_up_step.title." + id, form.getTitleTranslations()));
            existing.setDescriptionKeyId(entityTranslationService.upsertField(existing.getDescriptionKeyId(),
                    "cfg.top_up_step.description." + id, form.getDescriptionTranslations()));
            topUpStepRepository.save(existing);
        });
        return "redirect:/admin/config/top-up-steps?updated";
    }

    @PostMapping("/top-up-steps/delete/{id}")
    public String deleteTopUpStep(@PathVariable Long id) {
        topUpStepRepository.deleteById(id);
        return "redirect:/admin/config/top-up-steps?deleted";
    }
}
