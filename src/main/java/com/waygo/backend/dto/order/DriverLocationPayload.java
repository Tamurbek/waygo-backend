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
}
