package com.volcano.chat.service.impl;

import com.volcano.chat.entity.ChatLog;
import com.volcano.chat.mapper.ChatLogMapper;
import com.volcano.chat.service.ChatLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天日志服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLogServiceImpl implements ChatLogService {

    private final ChatLogMapper chatLogMapper;

    @Override
    public Long insert(ChatLog chatLog) {
        int rows = chatLogMapper.insert(chatLog);
        if (rows > 0) {
            log.info("Inserted chat log, recordId: {}", chatLog.getRecordId());
            return chatLog.getRecordId();
        }
        return null;
    }

    @Override
    public List<ChatLog> selectByUserId(String userId) {
        return chatLogMapper.selectByUserId(userId);
    }

    @Override
    public List<ChatLog> selectByRequestTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return chatLogMapper.selectByRequestTimeRange(startTime, endTime);
    }

    @Override
    public ChatLog selectById(Long recordId) {
        return chatLogMapper.selectByRecordId(recordId);
    }

    @Override
    public boolean updateById(ChatLog chatLog) {
        int rows = chatLogMapper.updateById(chatLog);
        return rows > 0;
    }

    @Override
    public boolean deleteById(Long recordId) {
        int rows = chatLogMapper.deleteByRecordId(recordId);
        return rows > 0;
    }
}
