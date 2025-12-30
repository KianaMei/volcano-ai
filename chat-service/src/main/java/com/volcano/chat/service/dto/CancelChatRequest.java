package com.volcano.chat.service.dto;

import lombok.Data;

/**
 * 取消对话请求 DTO
 */
@Data
public class CancelChatRequest {
    /**
     * 会话 ID (Conversation ID)
     */
    private String conversationId;

    /**
     * 对话 ID (Chat ID)
     */
    private String chatId;
}
