# Trading Engine Data Recovery Mechanism

This trading engine employs a highly reliable and high-performance persistence strategy, combining **Snapshots** and **Journaling** (Transaction Logging), to ensure that the system can be perfectly restored to its final state before an unexpected shutdown (e.g., power outage, crash).

The core of this mechanism is implemented in the `DiskSerializationProcessor.java` class, which acts like a flight recorder (black box), faithfully recording all operations that change the system's state, providing a solid foundation for data recovery.

---

## Parallel Processing, Consistency, and Data Safety

Before diving into the details of `DiskSerializationProcessor`, it is crucial to understand a core architectural design: **business processing and logging are executed in parallel**. This leads to two key scenarios regarding data consistency.

### Scenario 1: Journal is Written, but the Transaction is Not Completed in Memory

This situation is a normal failure mode that **must** be considered in the system design, and it has a **high probability of occurring**.

*   **Process**:
    1.  A `PLACE_ORDER` command is published to the `RingBuffer`.
    2.  `DiskSerializationProcessor`, being a very fast consumer, sees this command almost immediately and serializes it into the transaction journal file on disk. **At this point, the journal is written.**
    3.  Meanwhile, `RiskEngine` and `MatchingEngine` are processing this command. Assume the server suddenly loses power while the `MatchingEngine` is halfway through matching the order. **At this point, the transaction is not completed in memory.**

*   **How the System Recovers**:
    1.  The server restarts.
    2.  When `ExchangeCore` starts, it first loads the latest **state snapshot**, restoring the memory to the state at the time of the snapshot.
    3.  Then, it begins to **replay** all **transaction journals** created after the snapshot.
    4.  It will read the `PLACE_ORDER` command that was just recorded.
    5.  It will **re-execute this command completely in memory**: `RiskEngine` check -> `MatchingEngine` matching.
    6.  **Recovery complete.** The final state in memory is perfectly consistent with the state that would have existed if the recorded order had been successfully processed.

*   **Conclusion**: "Journal written but transaction not successful" is merely a **transient, inconsistent state**. Through log replay during disaster recovery, the system guarantees that all recorded commands will eventually be executed successfully, achieving eventual consistency.

### Scenario 2: Transaction is Completed in Memory, but the Journal Fails to be Written

This situation is **theoretically possible but is actively avoided in the design of `exchange-core`, making its probability of occurrence extremely low**.

*   **Why It's Unlikely**:
    1.  The job of `DiskSerializationProcessor` is simple: read a command from the `RingBuffer` -> serialize it -> write it sequentially to disk. This is a very fast, low-latency operation.
    2.  The core trading pipeline (`RiskEngine` -> `MatchingEngine`) involves much more complex work, including significant computation and memory access.
    3.  Therefore, in the vast majority of cases, the progress of `DiskSerializationProcessor` is always **ahead of or equal to** the progress of the core trading pipeline. A command is almost always logged before it is fully processed in memory.

*   **Under What Extreme Circumstances Could It Happen?**
    *   If disk I/O gets stuck for a very long time due to some reason (e.g., OS-level caching issues, disk hardware failure).
    *   Simultaneously, the core trading pipeline rapidly completes the processing of a command in memory, and `ResultsHandler` has even sent the result out via `IEventsHandler`.
    *   After this, the system crashes before `DiskSerializationProcessor` has had a chance to write the command to the journal.

*   **What Are the Consequences?**
    *   This is the **most dangerous** scenario because it means **data loss**.
    *   A client might have received a "transaction successful" notification, but after the system restarts and recovers, this transaction has "vanished" from memory because its corresponding command was never saved in the journal.

*   **How Does the System Mitigate This?**
    *   **fsync**: The configuration for `DiskSerializationProcessor` includes a key parameter to control whether to force an `fsync` call after each write. `fsync` compels the operating system to flush the file content from its memory buffer to the physical disk, ensuring the durability of the write. This sacrifices some performance but drastically reduces the risk of data loss.
    *   **High-Availability (HA) Architecture**: In a production environment, a primary-standby setup is typically deployed. The primary node processes transactions while simultaneously streaming the transaction journal to the standby node. The primary only confirms the transaction to the client after both nodes have confirmed that the journal has been written successfully. This way, even if the primary node crashes after writing the journal but before confirming to the client, the standby node can take over, guaranteeing no data loss. `exchange-core` itself does not provide this HA layer, but its journaling mechanism provides the foundation for implementing such an architecture.

### Summary

| Scenario                                   | Probability | Consequence                        | `exchange-core` Mitigation Strategy                                                              |
| ------------------------------------------ | ----------- | ---------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Journal written, transaction not done**  | **High**    | **None** (Temporary inconsistency) | **Log Replay**. This is a core part of the system design and guarantees eventual consistency.      |
| **Transaction done, journal not written**  | **Very Low**| **Data Loss** (Most dangerous)     | 1. **Configure `fsync`** to force disk flush.<br>2. Rely on an external **HA architecture** for journal redundancy. |

`exchange-core` elegantly handles the first scenario through its log-first and replay mechanism, and it provides support for addressing the second scenario by offering necessary underlying mechanisms (like `fsync` configuration and exportable logs).

## Core Component: `DiskSerializationProcessor` Deep Dive

`DiskSerializationProcessor` is the cornerstone of `exchange-core`'s data persistence and recovery capabilities. It does not participate in the core matching business, but its importance is paramount. It is primarily responsible for two things:

1.  **Journaling**: Real-time recording of all state-changing commands that enter the system.
2.  **Snapshotting**: Periodically saving the complete in-memory state of the system.

Let's delve into its working mechanism.

### 1. Journaling Explained

This is the most core and frequently executed operation of `DiskSerializationProcessor`, primarily implemented in the `writeToJournal` method. It acts as an independent consumer, listening to every `OrderCommand` on the `RingBuffer`.

*   **Command Filtering**: It first checks the command type. Only commands that **change the system state** (`isMutate() == true`), such as placing an order, canceling an order, or adjusting a balance, are recorded. Query-type commands (like requesting an order book) are ignored because they do not affect the system state.

*   **Binary Serialization**: For commands that need to be recorded, it writes them in a **highly compact binary format** into an in-memory `ByteBuffer` (`journalWriteBuffer`). It does not use Java's standard serialization but manually `put`s each field, one by one, to achieve extreme performance and space efficiency. For example, a `PLACE_ORDER` command is broken down into fields like `uid`, `symbol`, `orderId`, `price`, `size`, etc., and written sequentially into the `ByteBuffer`.

*   **Batch Writing and Smart Compression**:
    *   Commands are **not** written to disk one by one, as that would be too inefficient. They are first accumulated in the in-memory `journalWriteBuffer`.
    *   When the buffer is full (`position() >= journalBufferFlushTrigger`), or when a batch of processing is finished (`eob == true`), the entire buffer's content is written to the disk file in one go.
    *   **Smart Compression**: If the batch of data is small (less than `journalBatchCompressThreshold`), it **directly writes** the raw binary data. If the data volume is large, it first uses the **LZ4 algorithm** to compress the entire batch before writing it to disk. This is a very clever trade-off: for small data, the overhead of compression might be greater than the disk space saved; for large data, compression can significantly reduce disk I/O.

*   **File Rolling**:
    *   Journal files do not grow indefinitely. When a journal file reaches a preset threshold (`journalFileMaxSize`), `DiskSerializationProcessor` closes the current file and creates a new one (with an incremented `partitionId` in the filename). This makes log management and cleanup much easier.

*   **Flush Triggers**:
    *   The system is **not time-based** (e.g., flushing every few seconds) but is **event- and capacity-based** to strike a balance between performance and data safety. Flushing (forcing data from the memory buffer to the disk file) is primarily triggered by three conditions:
    1.  **End of [Batch](#batch)**: This is the main trigger. When the `RingBuffer`'s producer (`ExchangeApi`) sends the last command of a batch, it is marked with an `endOfBatch=true` flag. When `DiskSerializationProcessor` processes this command, it **immediately performs a flush**. This ensures that every batch of data is fully persisted, which is key to guaranteeing data consistency.
    2.  **Buffer Nearing Full**: This is a safety valve mechanism. If incoming commands fill up the `journalWriteBuffer` (default 256KB) before the end of a batch, the system will force a flush to prevent a buffer overflow. The specific threshold is `buffer size - 256 bytes`, which reserves enough space to write the next largest possible command.
    3.  **Specific Admin Commands**: When certain special system management commands are received, a flush is forced to ensure the atomicity of the operation. These commands include:
        *   `PERSIST_STATE_*`: Before taking a snapshot, all previous logs must be flushed to disk.
        *   `RESET`: During a system reset.
        *   `SHUTDOWN_SIGNAL`: During a graceful system shutdown.

### 2. Snapshotting Explained

The journal records the "process," while a snapshot records the "result." If there were only a journal, restoring a system after it has been running for a long time would require replaying a massive volume of logs, which would be very time-consuming. Snapshots are designed to solve this problem.

*   **Trigger Mechanism**: Snapshot generation is **not automatically triggered by an internal timer** but is **dynamically triggered by an external API call**. This process clearly separates the "request" from the "execution." The specific steps are as follows:

    1.  **External API Call**: An operations script or management service, acting as a client, calls the `persistState(snapshotId)` method in `ExchangeApi.java`. This `snapshotId` is typically a timestamp or a unique serial number and will become the unique identifier for the new snapshot.

    2.  **Generate Internal Command**: The `persistState` method creates an `OrderCommand` of type `PERSIST_STATE_MATCHING` or `PERSIST_STATE_RISK`. This command acts as an instruction, telling the matching engine or risk engine: "Please save your current state when you process this command."

    3.  **Command Enters Queue**: This `OrderCommand` is published to the system's core `RingBuffer` (a high-performance queue), where it waits in line with other trading commands (like place order, cancel order). This ensures strict ordering between snapshot operations and trading operations.

    4.  **Core Engine Executes Snapshot**:
        *   When `MatchingEngineRouter` or `RiskEngine` consumes this `PERSIST_STATE_*` command from the `RingBuffer`, it pauses processing new trading commands.
        *   It immediately serializes its **complete internal state** (for example, `MatchingEngineRouter` packages the entire order book, while `RiskEngine` packages all user account information) into an object.
        *   Finally, it calls the `serializationProcessor.storeData()` method, passing this state object, along with the `snapshotId`, to `DiskSerializationProcessor`.

    5.  **`DiskSerializationProcessor` Writes to Disk**: `DiskSerializationProcessor` receives the state object and `snapshotId`, and is responsible for compressing it (using LZ4) and writing it to a new snapshot file (`.ecs` file). The filename will include this `snapshotId` to enable precise lookups during future recoveries.

    This design decouples the ability to "execute a snapshot" from the strategy of "when to snapshot," allowing operators to perform snapshots during low-load periods (like after market close) to avoid impacting performance during peak trading hours.

*   **`storeData` (Saving State)**:
    *   When `RiskEngine` or `MatchingEngineRouter` receives a `PERSIST_STATE_*` command, they package their **complete internal state** (e.g., the entire `Map` of user accounts in `RiskEngine`) into a serializable object.
    *   They then call the `DiskSerializationProcessor.storeData()` method.
    *   The `storeData` method creates a new snapshot file and writes the incoming state object to it through an **LZ4 compression stream**.

*   **`loadData` (Loading State) and Memory Efficiency Optimization (`TODO`)**:
    *   When the system needs to recover, it calls the `loadData()` method to load a snapshot.
    *   **Current Implementation**: `loadData` decompresses and reads the entire snapshot file into memory at once before deserializing it. This can lead to huge memory spikes and even an `OutOfMemoryError` if the snapshot file is very large.
    *   **`// TODO improve reading algorithm`**: This comment in the source code is a performance optimization hint left by the author. A better "reading algorithm" would adopt a **streaming** approach—reading, decompressing, and deserializing on the fly—to keep the memory footprint small and predictable, making the recovery process more stable and efficient.

---

## Complete Recovery Process: Snapshot and Journal Synergy

When the trading engine starts, it executes a **fully automatic** recovery process, which **must** be a combination of "**load snapshot + replay incremental journal**".

#### Step 1: Determine the Recovery Base Point
You do not need to manually specify which file to load. You only need to provide a starting `snapshotId` via the `InitialStateConfiguration` when starting the engine. The engine will use this as a base point to automatically complete all subsequent operations.

#### Step 2: Load the Snapshot
During initialization, the various core components (`MatchingEngineRouter`, `RiskEngine`) automatically call `serializationProcessor.loadData()`, passing this `snapshotId` to load their respective parts of the snapshot data from disk, restoring their in-memory state to the moment the snapshot was created.

#### Step 3: Replay the Incremental Journal
After loading the snapshot, the system state is not yet up-to-date. At this point, the engine proceeds to execute the `replayJournalFull` method. This method will:
1.  Intelligently find all journal files generated **after that snapshot** based on the `snapshotId` configured at startup.
2.  Read these journal files sequentially, in ascending order of their `partitionId`.
3.  Parse the binary commands from the journal files one by one and call the corresponding methods in `ExchangeApi` to replay these operations.
4.  This process continues until it can no longer find the next partition file, at which point it assumes all relevant incremental journals have been replayed.

#### Step 4: Resume Normal Operation
Once all journals have been replayed, the data recovery is complete. The engine then calls the `enableJournaling` method to start logging new trading commands and switches to normal trading processing mode, ready to accept new requests.

---

## File Storage and Naming Convention

Snapshot and journal files are both stored in a configurable folder, defined by the `storageFolder` property of `DiskSerializationProcessorConfiguration`. The filenames contain rich metadata to ensure recovery accuracy.

*   **Snapshot File (`.ecs`)**: `exchangeId_snapshot_snapshotId_moduleCode_instanceId.ecs`
    *   Example: `NASDAQ_snapshot_1678886400_ME0.ecs`
*   **Journal File (`.ecj`)**: `exchangeId_journal_snapshotId_partitionId.ecj`
    *   Example: `NASDAQ_journal_1678886400_0001.ecj`
    *   **Key**: The journal filename includes the `snapshotId` it is based on, which forms the recovery chain from snapshot to journal. When a new snapshot is created, the new journal file sequence will be based on this new snapshot ID.

This design greatly simplifies operational work, making system restarts and disaster recovery very reliable and automated.

---

## Appendix: Core Terminology Explained

### Batch

In the context of this document, a "batch" is not a business concept but a core working model originating from the LMAX Disruptor framework, and it is key to achieving high performance.

*   **Definition**: A "batch" refers to a group of commands that are **published sequentially and at once** by a producer (`ExchangeApi`) onto the `RingBuffer`.
*   **How It Works**:
    1.  **Producer (`ExchangeApi`)**: When an external call is made to an `ExchangeApi` method, it acts as a producer and requests one or more consecutive "slots" (`OrderCommand` objects) from the `RingBuffer` (a circular queue).
    2.  **Batch Claiming**: For efficiency, the producer can claim a large batch of consecutive slots at once (e.g., via `ringBuffer.next(N)`) and then fill them with multiple commands.
    3.  **Batch Publishing**: After all slots are filled, the producer calls `ringBuffer.publish(low, high)` to "publish" the entire batch of commands at once, making them visible to all consumers.
*   **`endOfBatch` Flag**: When consumers (like `GroupingProcessor`, `DiskSerializationProcessor`) process commands on the `RingBuffer`, they can learn from the Disruptor framework whether the current command is the **last one** in that batch. This information is the `endOfBatch` flag.
*   **Significance**: `DiskSerializationProcessor` uses this `endOfBatch` flag as its primary flush trigger. This allows it to ensure that every logical batch of data is fully persisted without sacrificing too much performance, striking a fine balance between performance and data safety.
