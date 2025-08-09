# 交易引擎数据恢复机制

本交易引擎采用了一种非常可靠且高性能的持久化策略，结合了**快照（Snapshots）**和**事务日志（Journaling）**，以确保在系统因意外（如断电、崩溃）重启后，能够完美地恢复到中断前的最终状态。

这套机制的核心实现位于 `DiskSerializationProcessor.java` 类中，它像一个飞行记录仪（黑匣子），忠实地记录下所有会改变系统状态的操作，为数据恢复提供了坚实的基础。

---

## 核心组件：`DiskSerializationProcessor` 深度解析

`DiskSerializationProcessor` 是 `exchange-core` 的数据持久化和恢复能力的基石。它不参与核心的撮合业务，但其重要性不言而喻。它主要负责两件大事：

1.  **写日志 (Journaling)**: 实时记录所有进入系统的、会改变状态的命令。
2.  **拍快照 (Snapshotting)**: 定期保存系统的完整内存状态。

下面我们来深入了解它的工作机制。

### 1. 写日志 (Journaling) 详解

这是 `DiskSerializationProcessor` 最核心、最频繁执行的操作，主要由 `writeToJournal` 方法实现。它作为一个独立的消费者，监听 `RingBuffer` 上的每一个 `OrderCommand`。

*   **命令过滤**: 它首先会判断命令类型。只有那些会**改变系统状态**的命令（`isMutate() == true`）才会被记录，比如下单、取消订单、调整余额等。而查询类的命令（如查询订单簿）则会被忽略，因为它们不影响系统状态。

*   **二进制序列化**: 对于需要记录的命令，它会以一种**高度紧凑的二进制格式**将其写入一个内存中的 `ByteBuffer` (`journalWriteBuffer`)。它不使用 Java 的标准序列化，而是手动地、一个字段一个字段地 `put` 进去，以实现极致的性能和空间效率。例如，一个 `PLACE_ORDER` 命令会被拆解成 `uid`, `symbol`, `orderId`, `price`, `size` 等字段，然后依次写入 `ByteBuffer`。

*   **批量写入与智能压缩**:
    *   命令并**不是**每来一个就立刻写一次磁盘，这样效率太低。它们会先在内存的 `journalWriteBuffer` 中累积。
    *   当缓冲区满了（`position() >= journalBufferFlushTrigger`），或者一个批次处理结束时 (`eob == true`)，整个缓冲区的内容才会被一次性地写入磁盘文件。
    *   **智能压缩**: 如果这个批次的数据量比较小（小于 `journalBatchCompressThreshold`），它会**直接写入**原始的二进制数据。如果数据量比较大，它会先使用 **LZ4 算法**对整个批次的数据进行压缩，然后再写入磁盘。这是一种非常聪明的权衡：对于小数据，压缩的开销可能比节省的磁盘空间还大；对于大数据，压缩则能极大地减少磁盘 I/O。

*   **文件滚动 (Rolling)**:
    *   日志文件不会无限增大。当一个日志文件的大小达到预设的阈值 (`journalFileMaxSize`) 时，`DiskSerializationProcessor` 会关闭当前文件，并创建一个新的日志文件（文件名中的 `partitionId` 会递增）。这使得日志管理和清理变得更加容易。

### 2. 拍快照 (Snapshotting) 详解

日志记录了“过程”，而快照记录了“结果”。如果只有日志，那么在系统运行了很长时间后，恢复系统就需要重放海量的日志，会非常耗时。快照就是为了解决这个问题。

*   **触发机制**: 快照的生成**不是由内部定时器自动触发的**，而是由**外部通过 API 调用来动态触发**。这个流程清晰地分离了“请求”和“执行”，具体步骤如下：

    1.  **外部 API 调用**: 运维脚本或管理服务作为客户端，调用 `ExchangeApi.java` 中的 `persistState(snapshotId)` 方法。这个 `snapshotId` 通常是一个时间戳或一个唯一的序列号，它将成为新快照的唯一标识。

    2.  **生成内部命令**: `persistState` 方法会创建一个 `PERSIST_STATE_MATCHING` 或 `PERSIST_STATE_RISK` 类型的 `OrderCommand`。这个命令就像一个指令，告诉撮合引擎或风控引擎：“请在处理到这个命令时，保存你们的当前状态。”

    3.  **命令进入队列**: 这个 `OrderCommand` 被发布到系统核心的 `RingBuffer`（一个高性能队列）中，与其他交易命令（如下单、撤单）一起排队等待处理。这确保了快照操作与交易操作的严格顺序性。

    4.  **核心引擎执行快照**:
        *   当 `MatchingEngineRouter` 或 `RiskEngine` 从 `RingBuffer` 中消费到这个 `PERSIST_STATE_*` 命令时，它会暂停处理新的交易命令。
        *   它会立即将自己**完整的内部状态**（例如，`MatchingEngineRouter` 会打包整个订单簿，而 `RiskEngine` 会打包所有用户账户信息）序列化成一个对象。
        *   最后，它调用 `serializationProcessor.storeData()` 方法，将这个包含其完整状态的对象，连同 `snapshotId` 一起，交给 `DiskSerializationProcessor`。

    5.  **`DiskSerializationProcessor` 写盘**: `DiskSerializationProcessor` 接收到状态对象和 `snapshotId` 后，负责将其压缩（使用 LZ4）并写入到一个新的快照文件（`.ecs` 文件）中。文件名会包含这个 `snapshotId`，以便将来恢复时能够精确查找。

    这种设计将“执行快照”的能力和“决定何时快照”的策略完全解耦，允许运维人员在系统负载较低的时候（比如休市后）执行快照，从而避免影响交易高峰期的性能。

*   **`storeData` (保存状态)**:
    *   当 `RiskEngine` 或 `MatchingEngineRouter` 收到 `PERSIST_STATE_*` 命令时，它们会把自己**完整的内部状态**（比如 `RiskEngine` 里的所有用户账户 `Map`）打包成一个可序列化的对象。
    *   然后，它们会调用 `DiskSerializationProcessor.storeData()` 方法。
    *   `storeData` 方法会创建一个新的快照文件，并将传入的状态对象通过 **LZ4 压缩流**写入这个文件。

*   **`loadData` (加载状态) 与内存效率优化 (`TODO`)**:
    *   当系统启动需要恢复时，它会调用 `loadData()` 方法来加载快照。
    *   **当前实现**: `loadData` 会将整个快照文件一次性解压并读入内存，然后再进行反序列化。这在快照文件非常大时，可能会导致巨大的内存峰值，甚至 `OutOfMemoryError`。
    *   **`// TODO improve reading algorithm`**: 源码中的这个注释，正是作者留下的一个性能优化提示。一个更优的“读取算法”应该采用**流式处理（Streaming）**的方式，边读取、边解压、边反序列化，从而将内存峰值控制在一个很小的、可预测的范围内，使恢复过程更加稳定和高效。

---

## 完整恢复流程：快照与日志的协同工作

当交易引擎启动时，它会执行一个**全自动**的恢复流程，这个流程**必须**是“**加载快照 + 重放增量日志**”的结合。

#### 步骤一：确定恢复基点
您不需要手动指定加载哪个文件。您只需要在启动引擎时，通过 `InitialStateConfiguration` 提供一个起始的 `snapshotId`。引擎会以此为基点，全自动地完成后续所有操作。

#### 步骤二：加载快照
各个核心组件（`MatchingEngineRouter`, `RiskEngine`）在初始化时，会自动调用 `serializationProcessor.loadData()`，传入这个 `snapshotId`，从磁盘加载属于自己的那部分快照数据，将内存状态恢复到快照创建时的那一刻。

#### 步骤三：重放增量日志 (Replay)
加载完快照后，系统状态还不是最新的。此时，引擎会接着执行 `replayJournalFull` 方法。该方法会：
1.  根据启动时配置的 `snapshotId`，智能地找到所有**在该快照之后**生成的日志文件。
2.  按分区号 (`partitionId`) 从小到大的顺序，依次读取这些日志文件。
3.  逐条解析日志文件中的二进制命令，并调用 `ExchangeApi` 中对应的方法来重放这些操作。
4.  这个过程会一直持续，直到找不到下一个分区文件为止，此时就认为所有相关的增量日志都已重放完毕。

#### 步骤四：恢复正常运行
一旦所有的日志都重放完毕，数据恢复就完成了。此时，引擎会调用 `enableJournaling` 方法，开始为新的交易命令记录日志，然后切换到正常的交易处理模式，开始接受新的请求。

---

## 文件存储与命名规则

快照文件和日志文件都保存在一个可配置的文件夹中，由 `DiskSerializationProcessorConfiguration` 的 `storageFolder` 属性定义。文件名则包含了丰富的元数据，以确保恢复的准确性。

*   **快照文件 (`.ecs`)**: `exchangeId_snapshot_snapshotId_moduleCode_instanceId.ecs`
    *   例如: `NASDAQ_snapshot_1678886400_ME0.ecs`
*   **日志文件 (`.ecj`)**: `exchangeId_journal_snapshotId_partitionId.ecj`
    *   例如: `NASDAQ_journal_1678886400_0001.ecj`
    *   **关键**: 日志文件名中包含了它所基于的 `snapshotId`，这构成了从快照到日志的恢复链。当一个新的快照产生后，新的日志文件序列就会基于这个新的快照ID开始。

这种设计大大简化了运维工作，使得系统重启和灾难恢复变得非常可靠和自动化。
