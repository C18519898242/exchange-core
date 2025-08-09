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

- [ ] **架构设计与实现：接入网关层**
  - **描述**: 设计并实现一个或多个接入网关，负责处理外部客户端的连接和协议转换，例如通过 TCP/JSON 或 Websocket 接收请求。
  - **关键点**:
    - 网关负责网络通信、协议解析、用户认证和流量控制。
    - 网关应调用 `ExchangeApi` 提供的接口来执行交易操作。
    - 保持网关层“薄”，将核心交易逻辑保留在 `exchange-core` 内部。
