package com.waygo.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.waygo.backend.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtpVerificationRequest {
    private String phone;
    private String code;
    private String fullName;
    private String password;
    private User.Role role;
    
    @JsonProperty("isLogin")
    private boolean isLogin;

    private Boolean confirmRoleChange;
    private String referralCode;
}
