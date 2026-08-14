package com.waygo.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "map_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MapSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Kamera / Zoom ---
    @Builder.Default
    private Double driverBaseZoomLevel = 18.0;
    @Builder.Default
    private Double driverSpeedZoomOutAmount = 1.2;
    @Builder.Default
    private Double driverSheetZoomOutAmount = 1.2;
    @Builder.Default
    private Double driverDefaultZoom = 18.5;
    @Builder.Default
    private Double driverDefaultTilt = 0.0;
    @Builder.Default
    private Double userDefaultZoom = 18.5;
    @Builder.Default
    private Double userDefaultTilt = 35.0;
    @Builder.Default
    private Double userTrackingInitialZoom = 14.0;
    @Builder.Default
    private Double userCompatDefaultZoom = 15.0;

    // --- Road-snap / Look-ahead (faqat haydovchi ilovasi) ---
    @Builder.Default
    private Double stationaryDriftMeters = 6.0;
    @Builder.Default
    private Double gpsCourseTrustSpeedMps = 0.8;
    @Builder.Default
    private Double gpsCourseMinDisplacementMeters = 2.0;
    @Builder.Default
    private Integer cameraUpdateIntervalMs = 80;
    @Builder.Default
    private Integer routeTrimIntervalMs = 80;
    @Builder.Default
    private Double routeSnapMaxMeters = 20.0;
    @Builder.Default
    private Double lookAheadSeconds = 3.0;
    @Builder.Default
    private Double maxLookAheadMeters = 60.0;
    @Builder.Default
    private Double lookAheadMinSpeedMps = 3.0;
    @Builder.Default
    private Double arrivalThresholdMeters = 30.0;
    @Builder.Default
    private Double locationDistanceFilterMeters = 2.0;
    @Builder.Default
    private Integer locationIntervalMs = 1000;

    // --- Vizual (ranglar / chiziqlar) ---
    @Builder.Default
    private String routeStrokeColorHex = "#84D10D";
    @Builder.Default
    private Double routeStrokeWidth = 5.0;
    @Builder.Default
    private String routeOutlineColorHex = "#FFFFFF";
    @Builder.Default
    private Double routeOutlineOpacity = 0.9;
    @Builder.Default
    private Double routeOutlineWidth = 1.2;
    @Builder.Default
    private String defaultPolylineColorHex = "#000000";
    @Builder.Default
    private Double defaultPolylineWidth = 10.0;
    // Traffic (tirbandlik) darajalari bo'yicha marshrut rangi — driver ilovasi
    // yo'lovchi olib ketish xaritasida native palitra indeksiga (0-4) mos
    // keladi (yandex_mapkit forkidagi PolylineMapObjectController).
    @Builder.Default
    private String trafficColorFreeHex = "#00C853";
    @Builder.Default
    private String trafficColorLightHex = "#FFC107";
    @Builder.Default
    private String trafficColorHardHex = "#FF5722";
    @Builder.Default
    private String trafficColorVeryHardHex = "#D50000";
    @Builder.Default
    private String trafficColorUnknownHex = "#9E9E9E";

    // Xaritada 3D burchak (tilt gesture) yoqilganmi — ikkala ilovada ham
    // default o'chirilgan (2D-only).
    @Builder.Default
    private Boolean tilt3DEnabled = false;

    public boolean isTilt3DEnabled() {
        return Boolean.TRUE.equals(this.tilt3DEnabled);
    }

    // --- Yangilanish intervallari ---
    @Builder.Default
    private Integer driverLocationPollingSeconds = 10;
    @Builder.Default
    private Integer routeDrawAnimationMs = 1000;
    @Builder.Default
    private Integer routeCrossfadeAnimationMs = 400;

    // --- Haydovchi markeri (waygo_driver xaritasidagi o'z pozitsiyasi) ---
    // Null bo'lsa, ilova o'zining standart WayGo belgisidan foydalanadi.
    private String driverMarkerImageUrl;
    // "circle" | "square" | "roundedSquare"
    @Builder.Default
    private String driverMarkerShape = "circle";
    @Builder.Default
    private String driverMarkerBorderColorHex = "#E2E8F0";
    // Xaritadagi umumiy o'lcham koeffitsienti (zoom darajasiga qarab
    // dinamik hisoblangan bazaviy scale'ga qo'shimcha ko'paytiruvchi).
    @Builder.Default
    private Double driverMarkerSizeMultiplier = 0.8;

    // --- Yo'lovchini olib ketish markeri (Point A, waygo_driver'dagi
    // "olib ketish" xaritasida yo'lovchi turgan joyni ko'rsatadi) ---
    // Null bo'lsa, ilova o'zining standart (piyoda siluetli sariq) belgisidan foydalanadi.
    private String pickupMarkerImageUrl;
    // "circle" | "square" | "roundedSquare"
    @Builder.Default
    private String pickupMarkerShape = "circle";
    @Builder.Default
    private String pickupMarkerBorderColorHex = "#FFD600";
    @Builder.Default
    private Double pickupMarkerSizeMultiplier = 1.0;
}
