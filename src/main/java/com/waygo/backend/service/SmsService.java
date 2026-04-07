package com.waygo.backend.service;

public interface SmsService {
    void sendSms(String phone, String message);
}
