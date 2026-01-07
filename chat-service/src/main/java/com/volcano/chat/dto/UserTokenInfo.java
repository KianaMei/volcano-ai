package com.volcano.chat.dto;

/**
 * 用户 Token 信息 DTO
 * redis中存储的用户信息，包括手机号和coze验证token
 */
public record UserTokenInfo(String phone, String cozeToken) {}
