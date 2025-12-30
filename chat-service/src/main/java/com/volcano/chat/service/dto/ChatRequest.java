package com.volcano.chat.service.dto;

import lombok.Data;
import java.util.Map;

/**
 * 聊天请求 DTO
 */
@Data
public class ChatRequest {
    // userUuid 已移除，改为从 Token 获取

    /**
     * 消息内容
     */
    private String message;

    /**
     * 会话 ID（可选，用于继续已有对话）
     */
    private String conversationId;

    /**
     * 额外参数（可选）
     */
    private Map<String, Object> params;
}
