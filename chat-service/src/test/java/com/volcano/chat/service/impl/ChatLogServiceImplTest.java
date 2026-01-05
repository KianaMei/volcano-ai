package com.volcano.chat.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.volcano.chat.entity.ChatLog;
import com.volcano.chat.mapper.ChatLogMapper;
import com.volcano.chat.service.ChatLogService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ChatLogServiceImpl 独立测试")
class ChatLogServiceImplTest {

    private static HikariDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static ChatLogService chatLogService;
    private static ChatLogMapper chatLogMapper;

    private static Long testRecordId;
    private static final Long TEST_USER_ID = 99999L;

    @BeforeAll
    static void setUp() throws IOException {
        // 读取配置文件
        Properties props = new Properties();
        try (InputStream is = ChatLogServiceImplTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(is);
        }

        // 配置 HikariCP 数据源
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(props.getProperty("spring.datasource.url"));
        hikariConfig.setUsername(props.getProperty("spring.datasource.username"));
        hikariConfig.setPassword(props.getProperty("spring.datasource.password"));
        hikariConfig.setDriverClassName(props.getProperty("spring.datasource.driver-class-name"));
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(hikariConfig);

        // 配置 MyBatis-Plus
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatLogMapper.class);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);

        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        // 获取 Mapper
        SqlSession sqlSession = sqlSessionFactory.openSession(true);
        chatLogMapper = sqlSession.getMapper(ChatLogMapper.class);

        // 创建 Service
        chatLogService = new ChatLogServiceImpl(chatLogMapper);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("插入聊天记录")
    void insert() {
        ChatLog chatLog = new ChatLog();
        chatLog.setSessionId("test-session-001");
        chatLog.setUserId(TEST_USER_ID);
        chatLog.setUserQuestion("测试问题");
        chatLog.setAiAnswer("测试回答");
        chatLog.setRequestTime(LocalDateTime.now());
        chatLog.setResponseTime(LocalDateTime.now());

        testRecordId = chatLogService.insert(chatLog);

        assertNotNull(testRecordId);
    }

    @Test
    @Order(2)
    @DisplayName("根据ID查询")
    void selectById() {
        testRecordId = testRecordId != null ? testRecordId : 1L; // 确保有一个ID可用
        ChatLog result = chatLogService.selectById(testRecordId);

        assertNotNull(result);
        assertEquals(testRecordId, result.getRecordId());
        assertEquals(TEST_USER_ID, result.getUserId());
    }

    @Test
    @Order(3)
    @DisplayName("根据用户ID查询")
    void selectByUserId() {
        List<ChatLog> results = chatLogService.selectByUserId(TEST_USER_ID);

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("根据时间范围查询")
    void selectByRequestTimeRange() {
        LocalDateTime startTime = LocalDateTime.now().minusHours(1);
        LocalDateTime endTime = LocalDateTime.now().plusHours(1);

        List<ChatLog> results = chatLogService.selectByRequestTimeRange(startTime, endTime);

        assertNotNull(results);
    }

    @Test
    @Order(5)
    @DisplayName("更新聊天记录")
    void updateById() {
        testRecordId = testRecordId != null ? testRecordId : 1L; // 确保有一个ID可用
        ChatLog chatLog = chatLogService.selectById(testRecordId);
        chatLog.setAiAnswer("更新后的回答");

        boolean result = chatLogService.updateById(chatLog);

        assertTrue(result);
        ChatLog updated = chatLogService.selectById(testRecordId);
        assertEquals("更新后的回答", updated.getAiAnswer());
    }

    @Test
    @Order(6)
    @DisplayName("删除聊天记录")
    void deleteById() {
        boolean result = chatLogService.deleteById(testRecordId);

        assertTrue(result);
    }
}
