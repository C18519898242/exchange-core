# 交易引擎数据恢复机制

本交易引擎采用了一种非常可靠且高性能的持久化策略，结合了**快照（Snapshots）**和**事务日志（Journaling）**，以确保在系统重启后能够完全恢复数据。

## 恢复流程详解

整个恢复流程可以分为以下几个关键步骤：

### 1. 持久化机制

#### 快照 (Snapshotting)
- **作用**: 定期将核心组件（如订单簿、用户账户和风险数据）的完整内存状态，序列化后压缩（使用 LZ4 压缩算法），并保存到磁盘上的快照文件（`.ecs` 文件）。
- **实现**: 通过 `DiskSerializationProcessor.java` 中的 `storeData` 方法实现。
- **目的**: 快照相当于一个完整的数据库备份，它为数据恢复提供了一个坚实的基础，避免了从零开始重放所有历史记录，从而大大缩短了恢复时间。

#### 事务日志 (Journaling)
- **作用**: 在生成快照之后，所有进入系统的、会改变系统状态的命令（如下单、取消订单、资金调整等）都会被实时地、顺序地写入到日志文件（`.ecj` 文件）中。
- **实现**: 由 `DiskSerializationProcessor.java` 中的 `writeToJournal` 方法处理。为了提高性能，命令会先被放入缓冲区，然后批量写入磁盘，较大的批次还会被压缩。
- **目的**: 日志文件记录了自上一个快照以来发生的所有状态变更，确保了数据的完整性，保证两次快照之间的所有操作都不会丢失。

### 2. 重启恢复流程

当交易引擎启动时，它会执行以下恢复流程，这在 `ExchangeCore.java` 的 `startup()` 方法中被触发：

#### 步骤一：加载最新快照
引擎首先会查找并加载最新的可用快照文件。它会读取快照文件，解压并反序列化数据，从而将订单簿、用户账户等核心组件恢复到快照创建时的状态。这个过程由 `MatchingEngineRouter` 和 `RiskEngine` 在初始化时，通过调用 `serializationProcessor.loadData(...)` 来完成。

#### 步骤二：重放事务日志 (Replay)
加载完快照后，系统并不是最新的状态。此时，引擎会接着执行 `replayJournalFull` 方法。该方法会读取在上一个快照之后记录的所有日志文件，并按原始顺序，一条一条地“重放”日志中的每一条命令，将这些命令重新应用到内存中的数据上。通过重放所有增量日志，系统状态就能精确地恢复到宕机前的最后一刻。

#### 步骤三：恢复正常运行
一旦所有的日志都重放完毕，数据恢复就完成了。此时，引擎会调用 `enableJournaling` 方法，开始为新的交易命令记录日志，然后切换到正常的交易处理模式，开始接受新的请求。

## 数据丢失风险分析

**理论上，单纯的异步批量写入确实存在数据丢失的风险。** 如果系统在数据还停留在内存缓冲区而没有被刷入磁盘时突然断电，那么这部分数据就会丢失。

但是，本引擎通过以下机制将此风险降到最低：

1.  **关键时机的同步刷盘（Synchronous Flush）**:
    *   **批处理结束时 (`eob` - End of Batch)**: 系统对客户端的响应，一定是在该响应对应的命令及之前的所有命令都已落盘之后才会发生。
    *   **缓冲区满时**: 自动触发同步刷盘，防止数据积压在内存。
    *   **特定管理命令触发**: 在创建新快照等关键操作前，强制刷盘。

2.  **优雅停机（Graceful Shutdown）**:
    *   系统正常关闭时，会发送一个 `SHUTDOWN_SIGNAL` 信号，触发最终的同步刷盘操作，确保内存中所有数据都被写入文件。

因此，在正常操作流程和优雅停机的情况下，**不会引起交易数据的丢失**。对于任何已向客户端确认的交易，其数据都是安全的。

## 快照触发机制与配置

一个常见的问题是：快照是多长时间创建一次，以及在哪里进行配置？

在本引擎中，**快照的生成并不是基于固定的时间间隔（比如每小时一次），而是由外部通过 API 调用来动态触发的**。

这种设计提供了极大的灵活性，允许系统管理员或外部监控系统根据实际需求（例如，在非高峰时段、在重大市场事件后，或在达到一定交易量后）来决定何时创建快照。

### 触发机制

1.  **API 调用**: 外部系统通过调用 `ExchangeApi.java` 中的 `persistState(snapshotId)` 方法来发起一个快照请求。
2.  **命令分发**: 该 API 调用会创建一个 `PERSIST_STATE_MATCHING` 或 `PERSIST_STATE_RISK` 类型的 `OrderCommand`，并将其发布到系统内部的命令总线（Disruptor）中。
3.  **命令处理**:
    *   当核心处理单元（`MatchingEngineRouter` 和 `RiskEngine`）收到这个命令时，它们会立即调用 `serializationProcessor.storeData(...)` 方法，将自己当前的内存状态写入到快照文件中。
    *   当写盘处理器（`DiskSerializationProcessor`）完成快照后，它会记录下这个新的快照点，并开始一个新的日志文件序列。

### 如何配置

由于快照是按需触发的，所以**没有一个直接的“配置文件”来设置固定的快照间隔**。您需要有一个外部的调度程序（比如一个 Cron Job，或者一个专门的管理服务）来定期调用 `exchangeApi.persistState()` 方法。

这种模式避免了在交易高峰期因创建快照而可能引起的性能抖动，并将运维控制权完全交给了系统管理员。

## 文件存储位置与命名规则

快照文件和日志文件都保存在一个可配置的文件夹中。

**具体位置由以下两个配置共同决定：**

1.  **`DiskSerializationProcessorConfiguration`**: 这个配置类中有一个 `storageFolder` 属性，它直接定义了用于存放所有持久化数据的根目录。
2.  **`InitialStateConfiguration`**: 这个配置类中有一个 `exchangeId` 属性。这个 ID 会被用作文件名的一部分，以区分可能在同一台服务器上运行的多个不同交易所实例。

**文件命名规则：**

在 `DiskSerializationProcessor.java` 的 `resolveSnapshotPath` 方法中，我们可以看到快照文件的命名规则：

```java
folder.resolve(String.format("%s_snapshot_%d_%s%d.ecs", exchangeId, snapshotId, type.code, instanceId));
```

-   `folder`: 就是 `storageFolder` 定义的路径。
-   `exchangeId`: 交易所的唯一标识。
-   `snapshotId`: 快照的唯一 ID。
-   `type.code`: 模块类型（`RE` 代表风险引擎，`ME` 代表撮合引擎）。
-   `instanceId`: 模块的实例编号（因为可能存在多个撮合或风险引擎实例）。
-   `.ecs`: 文件扩展名（Exchange Core Snapshot）。

**举个例子：**

如果您的配置如下：

-   `storageFolder` = `/var/data/exchange`
-   `exchangeId` = `NASDAQ`

那么，一个撮合引擎（ME）实例0、ID为 `1678886400` 的快照文件，其完整路径将会是：

`/var/data/exchange/NASDAQ_snapshot_1678886400_ME0.ecs`

您可以在启动引擎时，通过 `DiskSerializationProcessorConfiguration` 来指定您希望的存储路径。

## Journaling 文件存储与记录

Journaling（事务日志）文件以高效的二进制格式存储，并采取了多种优化策略以实现极致的写入性能。

### 存储方式

1.  **二进制格式**: 所有命令都以紧凑的二进制格式写入，而不是文本，这大大减少了文件大小和 I/O 操作量。
2.  **直接内存缓冲区 (`ByteBuffer.allocateDirect`)**: 日志数据首先被写入一个位于堆外的直接内存缓冲区（Direct Buffer），这可以避免 JVM 在将数据写入磁盘时进行额外的数据复制，从而提高 I/O 效率。
3.  **批量写入与压缩**:
    *   命令不会逐条写入磁盘，而是先在内存缓冲区中累积。
    *   当缓冲区满或一批事件处理完成时，整个缓冲区的数据会一次性刷入磁盘。
    *   如果一个批次的数据量较大（由 `journalBatchCompressThreshold` 参数控制），在写入磁盘前，整个批次的数据会先用 **LZ4 算法进行压缩**。

### 记录位置与文件命名

Journaling 文件与快照文件存储在同一个根目录下，其命名规则在 `DiskSerializationProcessor.java` 的 `resolveJournalPath` 方法中定义：

```java
folder.resolve(String.format("%s_journal_%d_%04X.ecj", exchangeId, snapshotId, partitionId));
```

-   `folder`: 同样由 `storageFolder` 配置项决定。
-   `exchangeId`: 交易所的唯一标识。
-   `snapshotId`: **这个日志文件是基于哪个快照的**。这非常重要，它将日志与特定的快照关联起来，构成了恢复链。
-   `partitionId`: 日志文件的分区编号。因为单个日志文件有大小限制（由 `journalFileMaxSize` 配置），当一个文件写满后，系统会自动创建一个新的分区文件（`partitionId` 会递增），形成一个文件序列。
-   `.ecj`: 文件扩展名（Exchange Core Journal）。

**举个例子：**

假设 `storageFolder` 是 `/var/data/exchange`，`exchangeId` 是 `NASDAQ`，当前的快照 ID 是 `1678886400`。

-   第一个日志文件将会是：`/var/data/exchange/NASDAQ_journal_1678886400_0001.ecj`
-   当这个文件写满后，第二个日志文件将会是：`/var/data/exchange/NASDAQ_journal_1678886400_0002.ecj`

当系统创建一个新的快照（比如 ID 为 `1678889999`）后，新的日志文件序列就会基于这个新的快照ID开始：

-   `/var/data/exchange/NASDAQ_journal_1678889999_0001.ecj`

这种设计确保了数据恢复时，系统可以清晰地找到每个快照对应的、需要重放的日志文件序列。

## 自动化恢复流程

加载最新快照和对应的 Journal 文件是自动的吗？

是的，**整个加载和恢复过程是完全自动的**，但其行为依赖于启动时提供的配置。

具体来说，自动化体现在以下几个方面：

1.  **自动发现和加载**：
    *   当 `ExchangeCore` 启动时，它会根据 `InitialStateConfiguration` 中的 `snapshotId` 和 `exchangeId` 来确定要加载哪个快照。
    *   各个核心组件（`MatchingEngineRouter`, `RiskEngine`）在初始化时，会自动调用 `serializationProcessor.loadData()`，传入这个 `snapshotId`，从磁盘加载属于自己的那部分快照数据。

2.  **自动关联并重放日志**：
    *   在 `ExchangeCore` 的 `startup()` 方法中，关键的一步是调用 `serializationProcessor.replayJournalFullAndThenEnableJouraling()`。
    *   这个方法内部的 `replayJournalFull` 会自动执行以下操作：
        *   它使用启动时配置的 `snapshotId` 来构建需要查找的 Journal 文件名。
        *   它会自动按分区号（`partitionId`）从小到大的顺序，依次读取所有与该快照关联的日志文件。
        *   它会逐条解析日志文件中的二进制命令，并调用 `ExchangeApi` 中对应的方法来重放这些操作。
        *   这个过程会一直持续，直到找不到下一个分区文件为止，此时就认为所有相关的日志都已重放完毕。

**总结：**

您不需要手动去指定加载哪个快照文件或哪个日志文件。**您只需要在启动引擎时，通过 `InitialStateConfiguration` 提供一个起始的 `snapshotId`**。引擎会以此为基点，全自动地完成“加载快照 -> 查找并重放所有相关日志”这一系列恢复操作。

这种设计大大简化了运维工作，使得系统重启和灾难恢复变得非常可靠和自动化。
