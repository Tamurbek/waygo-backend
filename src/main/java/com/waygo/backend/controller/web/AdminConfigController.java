package com.waygo.backend.controller.web;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.repository.config.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

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

    @GetMapping("/tariffs")
    public String tariffs(Model model, @RequestParam(required = false) Long edit) {
        model.addAttribute("title", "Tarif Rejalari");
        model.addAttribute("tariffs", tariffPlanRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
        model.addAttribute("activeItem", "config_tariffs");
        if (edit != null) {
            tariffPlanRepository.findById(edit).ifPresent(tariff -> model.addAttribute("editItem", tariff));
        }
        return "admin/config/tariffs";
    }

    @PostMapping("/tariffs/add")
    public String addTariff(@ModelAttribute TariffPlan tariffPlan) {
        tariffPlan.setVip(false);
        tariffPlanRepository.save(tariffPlan);
        return "redirect:/admin/config/tariffs?success";
    }

    @PostMapping("/tariffs/edit/{id}")
    public String editTariff(@PathVariable Long id, @ModelAttribute TariffPlan form) {
        tariffPlanRepository.findById(id).ifPresent(existing -> {
            existing.setDuration(form.getDuration());
            existing.setDurationDays(form.getDurationDays());
            existing.setPrice(form.getPrice());
            existing.setOldPrice(form.getOldPrice());
            existing.setPopular(form.isPopular());
            existing.setActive(form.isActive());
            if (form.getFeatures() != null) existing.setFeatures(form.getFeatures());
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
        model.addAttribute("regions", regionRepository.findAllWithDistricts());
        model.addAttribute("activeItem", "config_regions");
        if (editRegion != null) {
            regionRepository.findById(editRegion).ifPresent(region -> model.addAttribute("editRegion", region));
        }
        if (editDistrict != null) {
            districtRepository.findById(editDistrict).ifPresent(district -> model.addAttribute("editDistrict", district));
        }
        return "admin/config/regions";
    }

    @PostMapping("/regions/add")
    public String addRegion(@ModelAttribute Region region) {
        region.setActive(true);
        regionRepository.save(region);
        return "redirect:/admin/config/regions?success";
    }

    @PostMapping("/regions/edit/{id}")
    public String editRegion(@PathVariable Long id, @ModelAttribute Region region) {
        regionRepository.findById(id).ifPresent(existing -> {
            existing.setName(region.getName());
            existing.setActive(region.isActive());
            existing.setLatitude(region.getLatitude());
            existing.setLongitude(region.getLongitude());
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
        model.addAttribute("brands", carBrandRepository.findAllWithModels());
        model.addAttribute("colors", carColorRepository.findAll());
        model.addAttribute("activeItem", "config_cars");
        
        if (editBrand != null) {
            carBrandRepository.findById(editBrand).ifPresent(brand -> model.addAttribute("editBrand", brand));
        }
        if (editModel != null) {
            carModelRepository.findById(editModel).ifPresent(carModel -> model.addAttribute("editModel", carModel));
        }
        if (editColor != null) {
            carColorRepository.findById(editColor).ifPresent(color -> model.addAttribute("editColor", color));
        }
        return "admin/config/cars";
    }

    @PostMapping("/cars/brands/add")
    public String addCarBrand(@ModelAttribute CarBrand brand) {
        carBrandRepository.save(brand);
        return "redirect:/admin/config/cars?success";
    }

    @PostMapping("/cars/brands/edit/{id}")
    public String editCarBrand(@PathVariable Long id, @ModelAttribute CarBrand brand) {
        brand.setId(id);
        carBrandRepository.save(brand);
        return "redirect:/admin/config/cars?updated";
    }

    @PostMapping("/cars/models/add")
    public String addCarModel(@ModelAttribute CarModel carModel, @RequestParam Long brandId) {
        CarBrand brand = carBrandRepository.findById(brandId).orElseThrow();
        carModel.setBrand(brand);
        carModelRepository.save(carModel);
        return "redirect:/admin/config/cars?success";
    }

    @PostMapping("/cars/models/edit/{id}")
    public String editCarModel(@PathVariable Long id, @ModelAttribute CarModel carModel, @RequestParam Long brandId) {
        carModel.setId(id);
        CarBrand brand = carBrandRepository.findById(brandId).orElseThrow();
        carModel.setBrand(brand);
        carModelRepository.save(carModel);
        return "redirect:/admin/config/cars?updated";
    }

    @PostMapping("/cars/colors/add")
    public String addCarColor(@ModelAttribute CarColor color) {
        carColorRepository.save(color);
        return "redirect:/admin/config/cars?success";
    }

    @PostMapping("/cars/colors/edit/{id}")
    public String editCarColor(@PathVariable Long id, @ModelAttribute CarColor color) {
        color.setId(id);
        carColorRepository.save(color);
        return "redirect:/admin/config/cars?updated";
    }

    @GetMapping("/services")
    public String services(Model model, @RequestParam(required = false) Long edit) {
        model.addAttribute("title", "Xizmatlar va Qulayliklar");
        model.addAttribute("services", serviceOptionRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
        model.addAttribute("activeItem", "config_services");
        if (edit != null) {
            serviceOptionRepository.findById(edit).ifPresent(service -> model.addAttribute("editItem", service));
        }
        return "admin/config/services";
    }

    @PostMapping("/services/add")
    public String addService(@ModelAttribute ServiceOption serviceOption,
                             @RequestParam(name = "active", required = false) String activeParam) {
        serviceOption.setActive("true".equals(activeParam));
        serviceOptionRepository.save(serviceOption);
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
        if (edit != null) {
            topUpStepRepository.findById(edit).ifPresent(step -> model.addAttribute("editItem", step));
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
        topUpStepRepository.save(topUpStep);
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
