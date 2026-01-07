package com.volcano.chat.service;

import com.volcano.chat.entity.ChatLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天日志服务接口
 */
public interface ChatLogService {

    /**
     * 插入聊天记录
     *
     * @param chatLog 聊天记录
     * @return 插入成功返回主键ID，失败返回null
     */
    Long insert(ChatLog chatLog);

    /**
     * 根据用户ID查询聊天记录
     *
     * @param userId 用户ID
     * @return 聊天记录列表
     */
    List<ChatLog> selectByUserId(String userId);

    /**
     * 根据时间范围查询聊天记录
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 聊天记录列表
     */
    List<ChatLog> selectByRequestTimeRange(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 根据记录ID查询
     *
     * @param recordId 记录ID
     * @return 聊天记录
     */
    ChatLog selectById(Long recordId);

    /**
     * 更新聊天记录
     *
     * @param chatLog 聊天记录
     * @return 是否成功
     */
    boolean updateById(ChatLog chatLog);

    /**
     * 逻辑删除聊天记录
     *
     * @param recordId 记录ID
     * @return 是否成功
     */
    boolean deleteById(Long recordId);
}
