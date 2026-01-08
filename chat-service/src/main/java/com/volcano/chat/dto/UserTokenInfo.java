package com.volcano.chat.dto;

/**
 * 用户 Token 信息 DTO
 * Redis 中存储的用户信息，包括手机号、Coze Token 和会话 ID
 * 
 * sessionId 与 Token 生命周期绑定，同一 Token 周期内的所有聊天记录共享同一个 sessionId
 */
public record UserTokenInfo(String phone, String cozeToken, String sessionId) {}
