package com.waygo.backend.service;

public interface EmailService {
    void sendPasswordResetEmail(String to, String resetLink);
}
