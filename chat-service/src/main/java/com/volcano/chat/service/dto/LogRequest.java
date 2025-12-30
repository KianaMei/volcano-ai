package com.volcano.chat.service.dto;

import lombok.Data;

@Data
public class LogRequest {
    private String userUuid;
    private String question;
    private String answer;
}
