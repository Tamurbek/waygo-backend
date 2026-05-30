package com.waygo.backend.exception;

import lombok.Getter;
import java.util.Map;

@Getter
public class PaymeException extends RuntimeException {
    private final int code;
    private final String data;
    private final Map<String, String> errorMessage;

    public PaymeException(int code, String ru, String uz, String en, String data) {
        super(en);
        this.code = code;
        this.data = data;
        this.errorMessage = Map.of("ru", ru, "uz", uz, "en", en);
    }
}
