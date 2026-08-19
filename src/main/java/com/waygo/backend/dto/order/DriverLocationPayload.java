package com.waygo.backend.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverLocationPayload {
    private Long orderId;
    private Long driverId;
    private Long userId;
    private Long passengerOrderId;
    private Double latitude;
    private Double longitude;
    private Double bearing;
    // The driver app's own live ETA (seconds remaining to its current
    // target), forwarded as-is so the passenger app can display the exact
    // same number instead of computing its own independent estimate — the
    // two used to drift apart since each side previously ran its own
    // separate, differently-timed routing calculation.
    private Double remainingDurationSeconds;
    private String driverName;
    private String carBrand;
    private String carModel;
    private String carColor;
    private String carNumber;
    private String carImageUrl;
    private String imageUrl;
}
