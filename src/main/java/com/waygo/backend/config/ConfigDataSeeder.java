package com.waygo.backend.config;

import com.waygo.backend.entity.config.*;
import com.waygo.backend.repository.config.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ConfigDataSeeder implements CommandLineRunner {

    private final TariffPlanRepository tariffPlanRepository;
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final CarBrandRepository carBrandRepository;
    private final CarModelRepository carModelRepository;
    private final CarColorRepository carColorRepository;
    private final TopUpStepRepository topUpStepRepository;

    @Override
    public void run(String... args) throws Exception {
        seedTariffPlans();
        seedRegionsAndDistricts();
        seedCarData();
        seedTopUpSteps();
    }

    private void seedTariffPlans() {
        if (tariffPlanRepository.count() == 0) {
            TariffPlan t1 = TariffPlan.builder()
                .duration("1 kunlik")
                .price(new BigDecimal("12000"))
                .oldPrice(new BigDecimal("15000"))
                .features(Arrays.asList("24 soat limitsiz buyurtma", "0% komissiya"))
                .isPopular(false)
                .isActive(true)
                .build();
                
            TariffPlan t3 = TariffPlan.builder()
                .duration("3 kunlik")
                .price(new BigDecimal("33000"))
                .oldPrice(new BigDecimal("45000"))
                .features(Arrays.asList("72 soat limitsiz buyurtma", "0% komissiya", "Kichik chegirma"))
                .isPopular(false)
                .isActive(true)
                .build();
                
            TariffPlan t7 = TariffPlan.builder()
                .duration("7 kunlik")
                .price(new BigDecimal("70000"))
                .oldPrice(new BigDecimal("84000"))
                .features(Arrays.asList("1 hafta limitsiz buyurtma", "0% komissiya", "Katta chegirma"))
                .isPopular(true)
                .isActive(true)
                .build();

            TariffPlan t15 = TariffPlan.builder()
                .duration("15 kunlik")
                .price(new BigDecimal("140000"))
                .oldPrice(new BigDecimal("180000"))
                .features(Arrays.asList("15 kun limitsiz buyurtma", "Ustuvorlik", "Super chegirma"))
                .isPopular(false)
                .isActive(true)
                .build();

            TariffPlan t30 = TariffPlan.builder()
                .duration("1 oy")
                .price(new BigDecimal("250000"))
                .oldPrice(new BigDecimal("360000"))
                .features(Arrays.asList("30 kun limitsiz buyurtma", "Premium status", "Maksimal chegirma"))
                .isPopular(false)
                .isActive(true)
                .build();

            tariffPlanRepository.saveAll(Arrays.asList(t1, t3, t7, t15, t30));
        }
    }

    private void seedRegionsAndDistricts() {
        if (regionRepository.count() == 0) {
            Region toshkentSh = regionRepository.save(Region.builder().name("Toshkent sh.").isActive(true).build());
            districtRepository.saveAll(Arrays.asList(
                District.builder().name("Yunusobod").region(toshkentSh).isActive(true).build(),
                District.builder().name("Chilonzor").region(toshkentSh).isActive(true).build(),
                District.builder().name("Mirzo Ulug'bek").region(toshkentSh).isActive(true).build()
            ));

            Region samarqand = regionRepository.save(Region.builder().name("Samarqand").isActive(true).build());
            districtRepository.saveAll(Arrays.asList(
                District.builder().name("Markaz").region(samarqand).isActive(true).build(),
                District.builder().name("Urgut").region(samarqand).isActive(true).build()
            ));
            
            // Just basic data, admins can add the rest
        }
    }

    private void seedCarData() {
        if (carBrandRepository.count() == 0) {
            CarBrand chevrolet = carBrandRepository.save(CarBrand.builder().name("Chevrolet").isActive(true).build());
            carModelRepository.saveAll(Arrays.asList(
                CarModel.builder().name("Cobalt").brand(chevrolet).isActive(true).build(),
                CarModel.builder().name("Gentra").brand(chevrolet).isActive(true).build(),
                CarModel.builder().name("Spark").brand(chevrolet).isActive(true).build(),
                CarModel.builder().name("Tracker").brand(chevrolet).isActive(true).build()
            ));

            CarBrand kia = carBrandRepository.save(CarBrand.builder().name("Kia").isActive(true).build());
            carModelRepository.saveAll(Arrays.asList(
                CarModel.builder().name("K5").brand(kia).isActive(true).build(),
                CarModel.builder().name("Seltos").brand(kia).isActive(true).build()
            ));
        }

        if (carColorRepository.count() == 0) {
            carColorRepository.saveAll(Arrays.asList(
                CarColor.builder().name("Oq").hexCode("#FFFFFF").isActive(true).build(),
                CarColor.builder().name("Qora").hexCode("#000000").isActive(true).build(),
                CarColor.builder().name("Kulrang").hexCode("#808080").isActive(true).build(),
                CarColor.builder().name("Sariq").hexCode("#FFFF00").isActive(true).build()
            ));
        }
    }

    private void seedTopUpSteps() {
        if (topUpStepRepository.count() == 0) {
            topUpStepRepository.saveAll(Arrays.asList(
                TopUpStep.builder()
                    .stepNumber(1)
                    .title("Payme ilovasini oching")
                    .description("Telefoningizda Payme ilovasini oching va asosiy sahifadagi 'To'lov' (Oplata) bo'limiga o'ting.")
                    .build(),
                TopUpStep.builder()
                    .stepNumber(2)
                    .title("'WayGO' xizmatini qidiring")
                    .description("Qidiruv satriga 'WayGO' deb yozing va qidiruv natijalaridan xizmatimizni tanlang.")
                    .build(),
                TopUpStep.builder()
                    .stepNumber(3)
                    .title("Billing ID va summani kiriting")
                    .description("Profil bo'limidagi 6 xonali Billing ID raqamingizni (masalan: WG12345) hamda to'ldirmoqchi bo'lgan summani kiriting.")
                    .build(),
                TopUpStep.builder()
                    .stepNumber(4)
                    .title("To'lovni tasdiqlang")
                    .description("Kiritilgan ma'lumotlar to'g'riligini tekshirib, to'lov tugmasini bosing. Mablag' hisobingizga darhol o'tkaziladi.")
                    .build()
            ));
        }
    }
}
