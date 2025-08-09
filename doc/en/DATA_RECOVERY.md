# Trading Engine Data Recovery Mechanism

This trading engine employs a highly reliable and high-performance persistence strategy, combining **Snapshots** and **Journaling** (Transaction Logging) to ensure full data recovery after a system restart.

## Detailed Recovery Process

The entire recovery process can be broken down into the following key steps:

### 1. Persistence Mechanism

#### Snapshotting
- **Role**: Periodically takes the complete in-memory state of core components (such as order books, user accounts, and risk data), serializes it, compresses it (using the LZ4 compression algorithm), and saves it to a snapshot file (`.ecs` file) on disk.
- **Implementation**: Implemented via the `storeData` method in `DiskSerializationProcessor.java`.
- **Purpose**: A snapshot is equivalent to a full database backup. It provides a solid foundation for data recovery, avoiding the need to replay all historical records from the beginning, thus significantly reducing recovery time.

#### Journaling (Transaction Logging)
- **Role**: After a snapshot is generated, all subsequent commands that enter the system and change its state (such as placing an order, canceling an order, adjusting funds, etc.) are written in real-time and sequentially to a journal file (`.ecj` file).
- **Implementation**: Handled by the `writeToJournal` method in `DiskSerializationProcessor.java`. To improve performance, commands are first placed in a buffer and then written to disk in batches. Larger batches are also compressed.
- **Purpose**: The journal file records all state changes that have occurred since the last snapshot, ensuring data integrity and guaranteeing that no operations between two snapshots are lost.

### 2. Restart Recovery Flow

When the trading engine starts, it executes the following recovery process, which is triggered in the `startup()` method of `ExchangeCore.java`:

#### Step 1: Load the Latest Snapshot
The engine first finds and loads the latest available snapshot file. It reads the snapshot file, decompresses and deserializes the data, thereby restoring core components like order books and user accounts to their state at the time the snapshot was created. This process is completed by `MatchingEngineRouter` and `RiskEngine` during their initialization by calling `serializationProcessor.loadData(...)`.

#### Step 2: Replay the Transaction Journal
After loading the snapshot, the system is not yet in its most recent state. At this point, the engine proceeds to execute the `replayJournalFull` method. This method reads all journal files recorded after the last snapshot and "replays" each command in the journal one by one, in their original order, reapplying them to the in-memory data. By replaying all incremental logs, the system state is precisely restored to the last moment before the crash.

#### Step 3: Resume Normal Operation
Once all journals have been replayed, data recovery is complete. The engine then calls the `enableJournaling` method to start logging new trading commands and switches to normal trading processing mode, ready to accept new requests.

## Data Loss Risk Analysis

**In theory, a purely asynchronous batch-writing approach does carry a risk of data loss.** If the system suddenly loses power while data is still in the memory buffer and has not yet been flushed to disk, that data will be lost.

However, this engine minimizes this risk through the following mechanisms:

1.  **Synchronous Flush at Critical Moments**:
    *   **End of Batch (`eob`)**: A response to a client is only sent after the command corresponding to that response, and all preceding commands, have been persisted to disk.
    *   **Buffer Full**: A synchronous flush is automatically triggered to prevent data from accumulating in memory.
    *   **Triggered by Specific Admin Commands**: A flush is forced before critical operations like creating a new snapshot.

2.  **Graceful Shutdown**:
    *   During a normal shutdown, a `SHUTDOWN_SIGNAL` is sent, triggering a final synchronous flush to ensure all data in memory is written to the files.

Therefore, under normal operating procedures and during a graceful shutdown, **there is no loss of transaction data**. For any transaction that has been confirmed to a client, its data is safe.

## Snapshot Trigger Mechanism and Configuration

A common question is: How often are snapshots created, and where is this configured?

In this engine, **snapshot generation is not based on a fixed time interval (e.g., once per hour) but is dynamically triggered by an external API call**.

This design offers great flexibility, allowing system administrators or external monitoring systems to decide when to create a snapshot based on actual needs (for example, during off-peak hours, after a major market event, or after a certain volume of trades has been reached).

### Trigger Mechanism

1.  **API Call**: An external system initiates a snapshot request by calling the `persistState(snapshotId)` method in `ExchangeApi.java`.
2.  **Command Dispatch**: This API call creates an `OrderCommand` of type `PERSIST_STATE_MATCHING` or `PERSIST_STATE_RISK` and publishes it to the system's internal command bus (Disruptor).
3.  **Command Processing**:
    *   When the core processing units (`MatchingEngineRouter` and `RiskEngine`) receive this command, they immediately call the `serializationProcessor.storeData(...)` method to write their current in-memory state to a snapshot file.
    *   When the disk writer (`DiskSerializationProcessor`) completes the snapshot, it records this new snapshot point and starts a new journal file sequence.

### How to Configure

Since snapshots are triggered on demand, **there is no direct "configuration file" to set a fixed snapshot interval**. You need an external scheduler (like a Cron Job or a dedicated management service) to periodically call the `exchangeApi.persistState()` method.

This model avoids potential performance jitter from creating snapshots during peak trading hours and gives full operational control to the system administrator.

## File Storage Location and Naming Convention

Snapshot and journal files are stored in a configurable folder.

**The specific location is determined by the following two configurations:**

1.  **`DiskSerializationProcessorConfiguration`**: This configuration class has a `storageFolder` property that directly defines the root directory for all persisted data.
2.  **`InitialStateConfiguration`**: This configuration class has an `exchangeId` property. This ID is used as part of the filename to distinguish between multiple different exchange instances that might be running on the same server.

**File Naming Convention:**

The naming convention for snapshot files can be found in the `resolveSnapshotPath` method of `DiskSerializationProcessor.java`:

```java
folder.resolve(String.format("%s_snapshot_%d_%s%d.ecs", exchangeId, snapshotId, type.code, instanceId));
```

-   `folder`: The path defined by `storageFolder`.
-   `exchangeId`: The unique identifier for the exchange.
-   `snapshotId`: The unique ID for the snapshot.
-   `type.code`: The module type (`RE` for Risk Engine, `ME` for Matching Engine).
-   `instanceId`: The instance number of the module (since there can be multiple matching or risk engine instances).
-   `.ecs`: The file extension (Exchange Core Snapshot).

**Example:**

If your configuration is as follows:

-   `storageFolder` = `/var/data/exchange`
-   `exchangeId` = `NASDAQ`

Then, a snapshot file for Matching Engine (ME) instance 0 with ID `1678886400` would have the full path:

`/var/data/exchange/NASDAQ_snapshot_1678886400_ME0.ecs`

You can specify your desired storage path via `DiskSerializationProcessorConfiguration` when starting the engine.

## Journaling File Storage and Recording

Journaling files are stored in an efficient binary format and employ several optimization strategies to achieve extreme write performance.

### Storage Method

1.  **Binary Format**: All commands are written in a compact binary format, not text, which significantly reduces file size and I/O operations.
2.  **Direct Memory Buffer (`ByteBuffer.allocateDirect`)**: Journal data is first written to a direct buffer outside the heap. This avoids extra data copying by the JVM when writing to disk, thereby improving I/O efficiency.
3.  **Batch Writing and Compression**:
    *   Commands are not written to disk one by one but are first accumulated in a memory buffer.
    *   When the buffer is full or a batch of events is processed, the entire buffer's data is flushed to disk at once.
    *   If a batch of data is large (controlled by the `journalBatchCompressThreshold` parameter), the entire batch is compressed with the **LZ4 algorithm** before being written to disk.

### Record Location and File Naming

Journaling files are stored in the same root directory as snapshot files. Their naming convention is defined in the `resolveJournalPath` method of `DiskSerializationProcessor.java`:

```java
folder.resolve(String.format("%s_journal_%d_%04X.ecj", exchangeId, snapshotId, partitionId));
```

-   `folder`: Also determined by the `storageFolder` configuration.
-   `exchangeId`: The unique identifier for the exchange.
-   `snapshotId`: **Which snapshot this journal file is based on**. This is very important as it associates the journal with a specific snapshot, forming a recovery chain.
-   `partitionId`: The partition number of the journal file. Because a single journal file has a size limit (configured by `journalFileMaxSize`), when a file is full, the system automatically creates a new partition file (`partitionId` increments), forming a sequence of files.
-   `.ecj`: The file extension (Exchange Core Journal).

**Example:**

Assume `storageFolder` is `/var/data/exchange`, `exchangeId` is `NASDAQ`, and the current snapshot ID is `1678886400`.

-   The first journal file will be: `/var/data/exchange/NASDAQ_journal_1678886400_0001.ecj`
-   When this file is full, the second will be: `/var/data/exchange/NASDAQ_journal_1678886400_0002.ecj`

When the system creates a new snapshot (e.g., with ID `1678889999`), the new journal file sequence will be based on this new snapshot ID:

-   `/var/data/exchange/NASDAQ_journal_1678889999_0001.ecj`

This design ensures that during data recovery, the system can clearly find the sequence of journal files that need to be replayed for each snapshot.

## Automated Recovery Process

Is the loading of the latest snapshot and its corresponding journal files automatic?

Yes, **the entire loading and recovery process is fully automatic**, but its behavior depends on the configuration provided at startup.

Specifically, the automation is reflected in the following aspects:

1.  **Automatic Discovery and Loading**:
    *   When `ExchangeCore` starts, it determines which snapshot to load based on the `snapshotId` and `exchangeId` in the `InitialStateConfiguration`.
    *   The core components (`MatchingEngineRouter`, `RiskEngine`) automatically call `serializationProcessor.loadData()` during their initialization, passing this `snapshotId` to load their respective parts of the snapshot data from disk.

2.  **Automatic Association and Replay of Journals**:
    *   A key step in the `startup()` method of `ExchangeCore` is the call to `serializationProcessor.replayJournalFullAndThenEnableJouraling()`.
    *   The `replayJournalFull` method within it automatically performs the following operations:
        *   It uses the `snapshotId` from the startup configuration to construct the names of the journal files to look for.
        *   It automatically reads all journal files associated with that snapshot in ascending order of their partition number (`partitionId`).
        *   It parses the binary commands from the journal files one by one and calls the corresponding methods in `ExchangeApi` to replay these operations.
        *   This process continues until it can no longer find the next partition file, at which point it assumes all relevant journals have been replayed.

**Conclusion:**

You do not need to manually specify which snapshot or journal file to load. **You only need to provide a starting `snapshotId` via the `InitialStateConfiguration` when you start the engine**. The engine will use this as a base point to automatically complete the entire recovery sequence of "load snapshot -> find and replay all related journals."

This design greatly simplifies operational work, making system restarts and disaster recovery very reliable and automated.
