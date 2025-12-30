package com.volcano.chat.service.controller;

import com.volcano.chat.service.dto.LogRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 日志控制器
 * 注意：数据库功能暂时禁用，日志只打印到控制台
 */
@Slf4j
@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = "*")
public class LogController {

    @PostMapping
    public String createLog(@RequestBody LogRequest request) {
        // 暂时只打印日志，不写数据库
        log.info("[ChatLog] User: {}, Q: {}, A: {}",
                request.getUserUuid(),
                request.getQuestion(),
                request.getAnswer());
        return "SUCCESS";
    }
}
