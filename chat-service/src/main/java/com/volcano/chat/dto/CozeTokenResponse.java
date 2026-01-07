package com.volcano.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Coze Token 响应 DTO
 */
@Getter
@AllArgsConstructor
public class CozeTokenResponse {
    private final String accessToken;
    private final int expiresIn;
}
