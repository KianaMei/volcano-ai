# 项目时序逻辑与架构文档

## 1. 系统架构概览

本系统采用 **代理模式** 架构，保护敏感信息（手机号、Coze API Key）不暴露给浏览器。

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| Chat Widget (iframe) | Vue 3 / Nuxt 3 | 聊天界面、SSE 渲染 |
| 网站A后端 | 任意（由客户实现） | 验证用户身份、S2S换Token |
| Chat Service 后端 | Java Spring Boot | 签发Token、代理Coze |
| Coze API | 外部服务 | LLM 推理 |

---

## 2. 完整时序图

```mermaid
sequenceDiagram
    autonumber
    
    actor User as 用户 (浏览器)
    participant Widget as 聊天挂件 (iframe)
    participant WebA as 网站A后端
    participant Chat as Chat服务 (我们)
    participant Coze as Coze API

    Note over User, Chat: === 阶段一：无感身份交换 ===
    
    User->>WebA: 打开页面，加载 iframe
    
    rect rgb(240, 255, 240)
    Note right of Widget: 挂件初始化
    Widget->>WebA: 1. 请求 Token (携带 Session Cookie)
    WebA->>WebA: 2. 验证 Cookie，识别用户身份<br/>获取手机号 138xxx
    WebA->>Chat: 3. S2S内网 POST /api/internal/session/create<br/>Body phone 138xxx
    Chat->>Chat: 4. 生成 Token tk_xxx<br/>绑定手机号，存入 Redis
    Chat-->>WebA: 5. 返回 Token tk_xxx expiresAt
    WebA-->>Widget: 6. 返回 Token 给 iframe
    end
    
    Note right of Widget: 此时挂件拿到了 Token<br/>全过程用户无感知<br/>手机号从未暴露给浏览器

    Note over User, Coze: === 阶段二：安全聊天 ===

    User->>Widget: 输入 你好
    
    rect rgb(224, 255, 255)
    Widget->>Chat: 7. POST /api/chat/send<br/>Headers X-Chat-Token tk_xxx<br/>Body message 你好
    
    Chat->>Chat: 8. 校验 Token<br/>从 Redis 查找 tk_xxx 对应 138xxx
    
    Chat->>Coze: 9. POST /v1/workflows/chat<br/>Auth Bearer COZE_KEY<br/>Body user_uuid 138xxx msg 你好
    
    loop SSE 流式响应
        Coze-->>Chat: event conversation.message.delta
        Chat-->>Widget: 透传 SSE 数据流
    end
    
    Coze-->>Chat: 10. event done
    Chat-->>Widget: 11. 关闭 SSE 连接
    end
    
    Widget-->>User: 显示 AI 回复
```

---

## 3. 关键安全点

1. **手机号从不暴露给浏览器**：只在 网站A后端 -> Chat后端 的 S2S 请求中传输
2. **Token 不透明**：即使被窃取，也无法反推手机号
3. **Coze Key 服务端保管**：前端永远不接触

---

## 4. 当前代码状态

| 接口 | 状态 | 说明 |
|------|------|------|
| `/api/mock/website-a/init` | Mock | 模拟网站A后端，开发测试用 |
| `/api/internal/session/create` | 生产 | 真正的 S2S 接口，生产环境网站A调用 |

生产部署时，iframe 应调用**网站A的真实后端**，由网站A后端调用我们的 `/api/internal/session/create`。
