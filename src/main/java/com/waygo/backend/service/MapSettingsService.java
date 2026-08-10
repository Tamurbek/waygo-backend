package com.waygo.backend.service;

import com.waygo.backend.entity.MapSettings;
import com.waygo.backend.repository.MapSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MapSettingsService {

    private final MapSettingsRepository repository;

    public MapSettings getSettings() {
        return repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(MapSettings.builder().build()));
    }

    @Transactional
    public MapSettings updateSettings(MapSettings newSettings) {
        MapSettings existing = getSettings();

        existing.setDriverBaseZoomLevel(newSettings.getDriverBaseZoomLevel());
        existing.setDriverSpeedZoomOutAmount(newSettings.getDriverSpeedZoomOutAmount());
        existing.setDriverSheetZoomOutAmount(newSettings.getDriverSheetZoomOutAmount());
        existing.setDriverDefaultZoom(newSettings.getDriverDefaultZoom());
        existing.setDriverDefaultTilt(newSettings.getDriverDefaultTilt());
        existing.setUserDefaultZoom(newSettings.getUserDefaultZoom());
        existing.setUserDefaultTilt(newSettings.getUserDefaultTilt());
        existing.setUserTrackingInitialZoom(newSettings.getUserTrackingInitialZoom());
        existing.setUserCompatDefaultZoom(newSettings.getUserCompatDefaultZoom());

        existing.setStationaryDriftMeters(newSettings.getStationaryDriftMeters());
        existing.setGpsCourseTrustSpeedMps(newSettings.getGpsCourseTrustSpeedMps());
        existing.setGpsCourseMinDisplacementMeters(newSettings.getGpsCourseMinDisplacementMeters());
        existing.setCameraUpdateIntervalMs(newSettings.getCameraUpdateIntervalMs());
        existing.setRouteTrimIntervalMs(newSettings.getRouteTrimIntervalMs());
        existing.setRouteSnapMaxMeters(newSettings.getRouteSnapMaxMeters());
        existing.setLookAheadSeconds(newSettings.getLookAheadSeconds());
        existing.setMaxLookAheadMeters(newSettings.getMaxLookAheadMeters());
        existing.setLookAheadMinSpeedMps(newSettings.getLookAheadMinSpeedMps());
        existing.setArrivalThresholdMeters(newSettings.getArrivalThresholdMeters());
        existing.setLocationDistanceFilterMeters(newSettings.getLocationDistanceFilterMeters());
        existing.setLocationIntervalMs(newSettings.getLocationIntervalMs());

        existing.setRouteStrokeColorHex(newSettings.getRouteStrokeColorHex());
        existing.setRouteStrokeWidth(newSettings.getRouteStrokeWidth());
        existing.setRouteOutlineColorHex(newSettings.getRouteOutlineColorHex());
        existing.setRouteOutlineOpacity(newSettings.getRouteOutlineOpacity());
        existing.setRouteOutlineWidth(newSettings.getRouteOutlineWidth());
        existing.setDefaultPolylineColorHex(newSettings.getDefaultPolylineColorHex());
        existing.setDefaultPolylineWidth(newSettings.getDefaultPolylineWidth());
        existing.setTrafficColorFreeHex(newSettings.getTrafficColorFreeHex());
        existing.setTrafficColorLightHex(newSettings.getTrafficColorLightHex());
        existing.setTrafficColorHardHex(newSettings.getTrafficColorHardHex());
        existing.setTrafficColorVeryHardHex(newSettings.getTrafficColorVeryHardHex());
        existing.setTrafficColorUnknownHex(newSettings.getTrafficColorUnknownHex());

        existing.setTilt3DEnabled(newSettings.isTilt3DEnabled());

        existing.setDriverLocationPollingSeconds(newSettings.getDriverLocationPollingSeconds());
        existing.setRouteDrawAnimationMs(newSettings.getRouteDrawAnimationMs());
        existing.setRouteCrossfadeAnimationMs(newSettings.getRouteCrossfadeAnimationMs());

        return repository.save(existing);
    }
}
