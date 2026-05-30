package com.waygo.backend.dto;

import lombok.Data;
import java.util.Map;

@Data
public class PaymeRequest {
    private String method;
    private Map<String, Object> params;
    private Long id;
}
