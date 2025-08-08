# 可靠市场数据网关设计方案

## 1. 背景与需求

在典型的交易系统中，外部客户端（如交易终端、行情展示应用等）需要实时接收来自撮合引擎（Matching Engine）的市场数据，例如成交记录（Trades）、订单簿更新（Order Book Updates）等。

一个核心的需求是，如果客户端因任何原因（如网络中断、重启、程序崩溃）与服务器断开连接，当它重新连接后，必须能够获取到在断线期间错过的所有市场数据，以保证其本地数据状态的完整性和连续性。这个机制通常被称为“可靠消息订阅”或“断线重连与消息追补”。

`exchange-core` 项目的核心设计专注于极致的撮合性能和自身的状态恢复，它通过 `IEventsHandler` 接口向外发布事件流，但并未内置一个让外部客户端可以从任意点“回溯”或“追补”历史行情的机制。

本文档旨在设计一个解决方案，以满足上述需求。

## 2. 设计方案：引入市场数据网关 (Market Data Gateway)

我们提出在核心引擎旁边增加一个专门用于处理和分发市场数据的服务层，称之为“市场数据网关”。这个网关将负责解决消息的持久化、回溯和实时推送问题，从而将行情分发的复杂性从核心引擎中解耦出来。

### 2.1. 核心架构改动

#### 2.1.1. 全局事件序列号 (Event ID)

为了能够精确定位每一条消息，我们需要为从撮合引擎发出的每一个事件分配一个全局唯一且严格单调递增的序列号。

1.  **修改事件对象**: 修改核心事件对象 `MatcherTradeEvent.java`，为其增加一个 `long eventId` 字段。
2.  **引入全局计数器**: 在撮合引擎的核心组件（如 `MatchingEngineRouter.java`）内部，维护一个全局的、原子性的 `long` 计数器。
3.  **分配ID**: 每当撮合引擎产生一个交易、撤单或拒绝事件时，就从这个计数器取一个新值，赋给事件的 `eventId` 字段。

这样，从引擎出来的每一条消息都有了一个独一无二且严格递增的“身份证号”，这是实现消息追补的基础。

### 2.2. 市场数据网关 (Market Data Gateway) 的设计

网关是一个独立的服务，它作为核心引擎和外部客户端之间的桥梁。

#### 2.2.1. 网关的核心职责

1.  **事件订阅**: 网关会作为一个特殊的内部客户端，通过 `IEventsHandler` 接口实时订阅核心引擎产生的所有事件流。
2.  **事件持久化**:
    *   网关接收到带有 `eventId` 的事件后，会立即将它们顺序写入一个专门为行情回放设计的持久化存储中。
    *   这个存储可以根据需求选择：
        *   **简单方案**: 基于文件的日志系统，类似于 `DiskSerializationProcessor` 的实现，将事件序列化后写入二进制文件。可以按 `eventId` 范围（如 `marketdata_1-100000.log`, `marketdata_100001-200000.log`）进行分片。
        *   **专业方案**: 引入外部专业消息队列，如 Apache Kafka, Pravega, 或 Pulsar，它们天然支持消息持久化和按位点回放。
3.  **客户端连接管理**: 维护所有外部客户端的连接。
4.  **消息分发与追补**: 处理客户端的请求，向其分发实时数据或历史追补数据。

#### 2.2.2. 从历史数据到实时数据的无缝切换

这是整个方案的核心难点：如何保证在追补历史数据的过程中，不错过任何实时数据，并在追补完成后平滑地过渡到实时流，确保消息的 **无重复、无遗漏**。

我们采用一种基于“统一事件源”和“分层缓存”的健壮方案。

**1. 统一的事件生产者**

为了从根本上避免竞争条件，网关内部应该只有一个**主生产者**负责向分发用的环形队列（Ring Buffer）发布事件。该主生产者的数据来源可以动态切换。

**2. 切换逻辑与分层缓存**

当客户端发起追补请求 (`lastEventId = N`) 时，网关根据追补数据量的大小，采用不同的策略：

**场景 A：小规模追补 (追补量 < 阈值，例如 100万条)**

适用于客户端仅短时间断线的情况。

1.  **设置数据源为历史日志**: 主生产者的数据源被设置为“历史日志读取器”。
2.  **实时数据入内存队列**: 与此同时，所有从主引擎收到的新实时事件，被放入一个临时的、有界内存队列中（如 `ArrayBlockingQueue`）。
3.  **发布历史数据**: 主生产者从历史日志读取 `eventId > N` 的事件，并发布到 Ring Buffer。
4.  **原子切换**:
    *   当历史日志读取完毕，主生产者会立即**清空内存队列**，将缓存的实时事件全部发布到 Ring Buffer。
    *   然后，主生产者的数据源被**原子性地切换**为“实时事件监听器”。
5.  **完成**: 之后所有实时事件都将直接被主生产者发布，完成无缝切换。

**场景 B：大规模追补 (追补量 >= 阈值)**

适用于客户端长时间宕机，需要追补海量数据的场景，以避免内存溢出。

1.  **启用“增量日志”**: 网关的实时捕获器发现当前是大规模追补模式，它会将所有新产生的实时事件写入一个临时的**增量日志文件** (`delta_journal.log`)，而不是写入内存。
2.  **并行处理**:
    *   **历史加载器**：从**主行情日志**中读取历史数据，发布到 Ring Buffer。
    *   **实时捕获器**：将**实时数据**写入**增量日志**文件。
3.  **第一阶段切换**: 当历史加载器读完主日志后，它的角色切换为“增量日志加载器”，开始读取并发布增量日志中的内容。
4.  **第二阶段切换 (最终切换)**:
    *   当增量日志也即将读取完毕时，系统可以预见追补即将完成。此时，可以安全地切换到**场景 A**中的内存队列模式。
    *   网关停止写入增量日志，开始将最后几条实时数据缓存到内存小队列。
    *   增量日志加载器读完文件后，清空内存队列，并将数据源最终切换到实时监听器。
    *   任务完成后，删除临时的增量日志文件。

### 3. 流程示意图 (大规模追补场景)

```mermaid
sequenceDiagram
    participant Client
    participant Gateway_MainProducer as "网关 (主生产者)"
    participant Gateway_LiveCapture as "网关 (实时捕获器)"
    participant MainLog as "主行情日志"
    participant DeltaLog as "增量日志"

    Client->>+Gateway_MainProducer: 连接请求 (lastEventId = N)
    Note over Gateway_LiveCapture, DeltaLog: 启用增量日志模式
    Gateway_LiveCapture->>+DeltaLog: 实时数据写入增量日志
    deactivate Gateway_LiveCapture

    Gateway_MainProducer->>+MainLog: 读取历史数据 (eventId > N)
    MainLog-->>-Gateway_MainProducer: 返回历史数据
    Gateway_MainProducer-->>Client: 推送历史数据

    Note over Gateway_MainProducer, MainLog: 主日志读取完毕
    deactivate MainLog

    Gateway_MainProducer->>+DeltaLog: 读取增量日志数据
    DeltaLog-->>-Gateway_MainProducer: 返回增量数据
    Gateway_MainProducer-->>Client: 推送增量追补数据

    Note over Gateway_MainProducer, DeltaLog: 增量日志读取完毕
    deactivate DeltaLog

    Note over Gateway_LiveCapture: 切换到内存队列缓存
    Gateway_LiveCapture->>Gateway_MainProducer: 缓存最后几条实时数据
    Note over Gateway_MainProducer: 清空内存队列, 切换数据源
    Gateway_MainProducer-->>Client: 推送最后几条追补数据

    loop 实时模式
        Gateway_LiveCapture->>Gateway_MainProducer: 实时事件
        Gateway_MainProducer-->>Client: 推送实时事件
    end
```

### 4. 总结

该方案通过引入一个专门的市场数据网关，有效地将数据分发的可靠性问题与核心撮合业务解耦。它提供了清晰的扩展路径（例如，从文件日志升级到Kafka），并定义了明确的客户端与服务器交互契约，能够健壮地解决客户端断线重连和数据追补的需求。
