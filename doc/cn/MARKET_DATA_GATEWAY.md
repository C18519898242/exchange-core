# 可靠的行情网关设计

## 1. 背景与需求

在一个典型的撮合交易系统中，外部客户端（如交易终端、行情展示应用等）需要从撮合引擎接收实时的市场数据，如成交、订单簿更新等。

一个核心的需求是，如果客户端因为任何原因（如网络中断、重启、应用崩溃）与服务器断开连接，它必须能够在重新连接后，获取所有错过的市场数据，以保证其本地数据状态的完整性和连续性。这种机制通常被称为“可靠消息订阅”或“断线重连与消息恢复”。

`exchange-core` 项目的核心设计专注于极致的撮合性能和自身的状态恢复，它通过 `IEventsHandler` 接口发布事件流，但本身并没有内置一个机制，让外部客户端可以从任意一个点“回放”或“追赶”历史行情。

本文档旨在设计一个方案来满足这一需求。

## 2. 设计思路：引入行情网关

我们建议在核心引擎旁增加一个专门用于处理和分发行情数据的服务层，我们称之为“行情网关”（Market Data Gateway）。这个网关将负责消息的持久化、回放和实时推送，从而将行情分发的复杂性与核心引擎解耦。

### 2.1. 核心架构改造

#### 2.1.1. 全局事件序列号 (Event ID)

为了能够精确定位每一条消息，我们需要为从撮合引擎发出的每一个事件，都分配一个全局唯一且严格单调递增的序列号。

1.  **修改事件对象**：在核心的事件对象 `MatcherTradeEvent.java` 中增加一个 `long eventId` 字段。
2.  **引入全局计数器**：在撮合引擎的核心组件（如 `MatchingEngineRouter.java`）内部，维护一个全局的、原子性的 `long` 计数器。
3.  **分配ID**：每当撮合引擎产生一个成交、撤单或拒绝事件时，就从这个计数器取一个新值，赋给事件的 `eventId` 字段。

如此一来，从引擎出来的每一条消息，都有了一个唯一且严格递增的“身份证号”，这是实现消息恢复的基础。

### 2.2. 行情网关设计

网关是一个独立的服务，它作为核心引擎和外部客户端之间的桥梁。

#### 2.2.1. 网关的核心职责

1.  **事件订阅**：网关将作为一个特殊的内部客户端，通过 `IEventsHandler` 接口，实时订阅来自核心引擎的全部事件流。
2.  **事件持久化**：
    *   当收到一个带有 `eventId` 的事件后，网关会立即将其顺序写入一个专为行情回放设计的持久化存储中。
    *   这个存储可以根据需求选择：
        *   **简单方案**：基于文件的日志系统，类似 `DiskSerializationProcessor` 的实现，将事件序列化后写入二进制文件。这些文件可以按 `eventId` 范围进行分片（如 `marketdata_1-100000.log`, `marketdata_100001-200000.log`）。
        *   **专业方案**：引入外部专业的、支持按 offset 回放的消息队列，如 Apache Kafka, Pravega, Pulsar。
3.  **客户端连接管理**：维护所有外部客户端的连接。
4.  **消息分发与恢复**：处理客户端的请求，向其分发实时数据或历史恢复数据。

#### 2.2.2. 历史数据到实时数据的无缝切换

这是整个方案的核心难点：如何在恢复历史数据的同时，不错过任何实时产生的数据，并在恢复完成后，平滑地切换到实时流，保证 **不重不漏**。

我们将采用一个基于“统一事件源”和“分层缓存”的健壮方案。

**1. 统一的事件生产者**

为了从根本上避免竞态条件，网关应该只有一个 **主生产者** 负责向分发环形缓冲区（Ring Buffer）发布事件。这个主生产者的“数据源”可以动态切换。

**2. 切换逻辑与分层缓存**

当客户端发起恢复请求（`lastEventId = N`）时，网关根据需要恢复的数据量大小，采取不同的策略：

**场景A：小规模恢复 (恢复量 < 阈值，如100万条)**

适用于客户端只掉线了很短时间的场景。

1.  **数据源设为历史日志**：主生产者的“数据源”被设置为“历史日志读取器”。
2.  **实时数据入内存队列**：与此同时，所有从主引擎新接收到的实时事件，被放入一个临时的、有界的内存队列中（如 `ArrayBlockingQueue`）。
3.  **发布历史数据**：主生产者从历史日志中读取 `eventId > N` 的事件，并将其发布到 Ring Buffer。
4.  **原子切换**：
    *   当历史日志读取完毕后，主生产者立即 **清空内存队列**，将所有缓存的实时事件发布到 Ring Buffer。
    *   然后，主生产者的“数据源”被 **原子地切换** 为“实时事件监听器”。
5.  **完成**：之后，所有实时事件都由主生产者直接发布，无缝切换完成。

**场景B：大规模恢复 (恢复量 >= 阈值)**

适用于客户端宕机了很长时间，需要恢复海量数据，以避免内存溢出的场景。

1.  **启用“增量日志”**：网关的实时捕获器检测到处于大规模恢复模式，将所有新产生的实时事件写入一个临时的 **增量日志文件** (`delta_journal.log`)，而不是写入内存。
2.  **并行处理**：
    *   **历史加载器**：从 **主行情日志** 读取历史数据，并发布到 Ring Buffer。
    *   **实时捕获器**：将 **实时数据** 写入 **增量日志** 文件。
3.  **第一阶段切换**：当历史加载器读完主日志后，它的角色切换为“增量日志加载器”，开始读取并发布增量日志的内容。
4.  **第二阶段切换（最终切换）**：
    *   当增量日志即将读取完毕时，系统可以预见到恢复即将完成。此时，可以安全地切换到 **场景A** 的内存队列模式。
    *   网关停止写入增量日志，开始将最后几条实时事件缓存到一个小的内存队列中。
    *   增量日志加载器读完文件后，清空内存队列，并最终将数据源切换为实时监听器。
    *   任务完成后，临时的增量日志文件被删除。

### 3. 流程图 (大规模恢复场景)

```mermaid
sequenceDiagram
    participant Client
    participant Gateway_MainProducer as "网关 (主生产者)"
    participant Gateway_LiveCapture as "网关 (实时捕获)"
    participant MainLog as "主行情日志"
    participant DeltaLog as "增量日志"

    Client->>+Gateway_MainProducer: 连接请求 (lastEventId = N)
    Note over Gateway_LiveCapture, DeltaLog: 启用增量日志模式
    Gateway_LiveCapture->>+DeltaLog: 实时数据写入增量日志
    deactivate Gateway_LiveCapture

    Gateway_MainProducer->>+MainLog: 读取历史数据 (eventId > N)
    MainLog-->>-Gateway_MainProducer: 返回历史数据
    Gateway_MainProducer-->>Client: 推送历史数据

    Note over Gateway_MainProducer, MainLog: 主日志读取完成
    deactivate MainLog

    Gateway_MainProducer->>+DeltaLog: 读取增量日志数据
    DeltaLog-->>-Gateway_MainProducer: 返回增量数据
    Gateway_MainProducer-->>Client: 推送增量恢复数据

    Note over Gateway_MainProducer, DeltaLog: 增量日志读取完成
    deactivate DeltaLog

    Note over Gateway_LiveCapture: 切换为内存队列缓存
    Gateway_LiveCapture->>Gateway_MainProducer: 缓存最后几条实时事件
    Note over Gateway_MainProducer: 清空内存队列, 切换数据源
    Gateway_MainProducer-->>Client: 推送最后几条恢复事件

    loop 实时模式
        Gateway_LiveCapture->>Gateway_MainProducer: 实时事件
        Gateway_MainProducer-->>Client: 推送实时事件
    end
```

### 4. 总结

该方案通过引入专门的行情网关，有效地将数据分发的可靠性与核心撮合业务解耦。它提供了清晰的扩展路径（如从文件日志升级到Kafka），并定义了清晰的客户端-服务器交互契约，健壮地解决了客户端断线重连和数据恢复的需求。
