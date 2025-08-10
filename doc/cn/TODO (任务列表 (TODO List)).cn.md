# 任务列表 (TODO List)

本文档用于跟踪和管理 `exchange-core` 项目的未来开发任务和功能规划。

## 待办任务

- [ ] **功能实现：市价单按数量买入**
  - **描述**: 实现 `ExchangeApi` 中的 `placeMarketBuyOrderByQuantity()` 方法，将“按数量”的市价买入请求安全地转换为“按金额”的请求。
  - **关键点**:
    - 需要向 `ExchangeApi` 注入价格查询函数。
    - 需要在 `CoreSymbolSpecification` 中增加风险乘数配置。
    - 实现“尽力而为”的资金计算逻辑。
  - **相关文档**: [深度解析：市价单按数量买入的设计与实现](./DEEP_DIVE_MARKET_BUY_BY_QUANTITY.cn.md)

- [ ] **架构实现：gRPC 网关集群**
  - **描述**: 根据最终确定的架构方案，实现基于 gRPC 的高性能网关集群。
  - **相关文档**: [网关架构设计](./GATEWAY_ARCHITECTURE (网关架构设计).cn.md)
  - **子任务**:
    - [ ] **项目设置**:
      - [ ] 创建新的 Maven 模块 `gateway`。
      - [ ] 在 `pom.xml` 中添加 `grpc-java`, `protobuf-java`, `chronicle-queue`, `bouncycastle` 等依赖。
    - [ ] **Protobuf 定义**:
      - [ ] 编写 `.proto` 文件，定义所有服务 (`OrderService`, `AdminService` 等) 和消息 (`LoginRequest`, `PlaceOrderRequest`, `BalanceUpdateEvent` 等)。
    - [ ] **核心组件实现**:
      - [ ] 实现基于 `Argon2id` 的密码哈希生成工具和验证逻辑。
      - [ ] 实现 `AuthService`，处理基于用户名/密码的登录和会话管理。
      - [ ] 实现 `事件路由器 (EventRouter)`，订阅核心事件并分发到不同的 Chronicle Queue。
      - [ ] 实现 `余额处理器 (BalanceProcessor)`，作为有状态服务生成余额更新事件。
    - [ ] **网关服务实现**:
      - [ ] 实现 `OrderGateway` (私有交易通道)。
      - [ ] 实现 `AdminGateway` (内部管理通道)。
      - [ ] 实现 `MarketDataGateway` (公开行情通道)。
      - [ ] 实现 `TradeDataGateway` (内部成交数据通道)。
    - [ ] **测试**:
      - [ ] 编写集成测试，模拟客户端连接、认证、下单和订阅事件，验证整个流程的正确性。
