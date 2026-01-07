package com.volcano.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.volcano.chat.entity.ChatLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatLogMapper extends BaseMapper<ChatLog> {

    @Select("SELECT * FROM chat_logs WHERE record_id = #{recordId} AND deleted = 0")
    ChatLog selectByRecordId(@Param("recordId") Long recordId);

    @Select("SELECT * FROM chat_logs WHERE user_id = #{userId} AND deleted = 0")
    List<ChatLog> selectByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM chat_logs WHERE request_time >= #{startTime} AND request_time <= #{endTime} AND deleted = 0")
    List<ChatLog> selectByRequestTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Update("UPDATE chat_logs SET deleted = 1 WHERE record_id = #{recordId}")
    int deleteByRecordId(@Param("recordId") Long recordId);
}
