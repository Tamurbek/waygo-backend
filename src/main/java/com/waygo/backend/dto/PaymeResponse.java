package com.waygo.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymeResponse {
    private Object result;
    private PaymeError error;
    private Long id;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymeError {
        private Integer code;
        private Object message;
        private String data;
    }
}
