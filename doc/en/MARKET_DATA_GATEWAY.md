# Design for a Reliable Market Data Gateway

## 1. Background and Requirements

In a typical trading system, external clients (such as trading terminals, market data display applications, etc.) need to receive real-time market data from the Matching Engine, such as trades, order book updates, and more.

A core requirement is that if a client disconnects from the server for any reason (e.g., network interruption, restart, application crash), it must be able to retrieve all missed market data upon reconnection to ensure the integrity and continuity of its local data state. This mechanism is often referred to as "reliable message subscription" or "reconnection with message recovery."

The core design of the `exchange-core` project focuses on extreme matching performance and its own state recovery. It publishes an event stream via the `IEventsHandler` interface but does not have a built-in mechanism for external clients to "replay" or "catch up" on historical market data from an arbitrary point.

This document aims to design a solution to meet this requirement.

## 2. Design: Introducing a Market Data Gateway

We propose adding a dedicated service layer next to the core engine for processing and distributing market data, which we will call the "Market Data Gateway." This gateway will be responsible for handling message persistence, replay, and real-time push, thereby decoupling the complexity of market data distribution from the core engine.

### 2.1. Core Architectural Changes

#### 2.1.1. Global Event Sequence ID (Event ID)

To accurately locate every message, we need to assign a globally unique and strictly monotonically increasing sequence number to every event emitted from the matching engine.

1.  **Modify Event Object**: Add a `long eventId` field to the core event object, `MatcherTradeEvent.java`.
2.  **Introduce a Global Counter**: Maintain a global, atomic `long` counter inside a core component of the matching engine (such as `MatchingEngineRouter.java`).
3.  **Assign ID**: Whenever the matching engine produces a trade, cancel, or reject event, it takes a new value from this counter and assigns it to the event's `eventId` field.

With this, every message coming out of the engine has a unique and strictly increasing "ID number," which is the foundation for implementing message recovery.

### 2.2. Market Data Gateway Design

The gateway is an independent service that acts as a bridge between the core engine and external clients.

#### 2.2.1. Core Responsibilities of the Gateway

1.  **Event Subscription**: The gateway will act as a special internal client, subscribing to the entire event stream from the core engine in real-time via the `IEventsHandler` interface.
2.  **Event Persistence**:
    *   Upon receiving an event with an `eventId`, the gateway will immediately write it sequentially to a persistent storage designed for market data replay.
    *   This storage can be chosen based on requirements:
        *   **Simple Solution**: A file-based logging system, similar to the implementation of `DiskSerializationProcessor`, where events are serialized and written to binary files. These files can be sharded by `eventId` range (e.g., `marketdata_1-100000.log`, `marketdata_100001-200000.log`).
        *   **Professional Solution**: Introduce an external, professional message queue like Apache Kafka, Pravega, or Pulsar, which natively support message persistence and replay by offset.
3.  **Client Connection Management**: Maintain connections for all external clients.
4.  **Message Distribution and Recovery**: Handle client requests, distributing real-time data or historical recovery data to them.

#### 2.2.2. Seamless Transition from Historical to Real-Time Data

This is the core challenge of the entire solution: how to ensure that no real-time data is missed while recovering historical data, and how to smoothly transition to the real-time stream after recovery is complete, guaranteeing **no duplicates and no omissions**.

We will adopt a robust solution based on a "unified event source" and "layered caching."

**1. Unified Event Producer**

To fundamentally avoid race conditions, the gateway should have only one **main producer** responsible for publishing events to the distribution ring buffer. The data source for this main producer can be dynamically switched.

**2. Switching Logic and Layered Caching**

When a client initiates a recovery request (`lastEventId = N`), the gateway adopts different strategies based on the volume of data to be recovered:

**Scenario A: Small-Scale Recovery (Recovery volume < Threshold, e.g., 1 million events)**

Suitable for clients that were disconnected for only a short period.

1.  **Set Data Source to Historical Log**: The main producer's data source is set to the "historical log reader."
2.  **Queue Real-Time Data in Memory**: Simultaneously, all new real-time events received from the main engine are placed into a temporary, bounded in-memory queue (e.g., `ArrayBlockingQueue`).
3.  **Publish Historical Data**: The main producer reads events with `eventId > N` from the historical log and publishes them to the Ring Buffer.
4.  **Atomic Switch**:
    *   When the historical log has been fully read, the main producer immediately **drains the in-memory queue**, publishing all cached real-time events to the Ring Buffer.
    *   Then, the main producer's data source is **atomically switched** to the "real-time event listener."
5.  **Completion**: Afterward, all real-time events are directly published by the main producer, completing the seamless transition.

**Scenario B: Large-Scale Recovery (Recovery volume >= Threshold)**

Suitable for clients that have been down for a long time and need to recover a massive amount of data, to avoid memory overflow.

1.  **Enable "Delta Journal"**: The gateway's real-time capturer detects that it is in large-scale recovery mode and writes all newly generated real-time events to a temporary **delta journal file** (`delta_journal.log`) instead of into memory.
2.  **Parallel Processing**:
    *   **Historical Loader**: Reads historical data from the **main market data log** and publishes it to the Ring Buffer.
    *   **Real-Time Capturer**: Writes **real-time data** to the **delta journal** file.
3.  **First-Stage Switch**: When the historical loader finishes reading the main log, its role switches to "delta journal loader," and it begins reading and publishing content from the delta journal.
4.  **Second-Stage Switch (Final Switch)**:
    *   As the delta journal is about to be fully read, the system can anticipate that recovery is nearing completion. At this point, it can safely switch to the in-memory queue mode from **Scenario A**.
    *   The gateway stops writing to the delta journal and starts caching the last few real-time events in a small in-memory queue.
    *   After the delta journal loader finishes reading the file, it drains the in-memory queue and finally switches its data source to the real-time listener.
    *   After the task is complete, the temporary delta journal file is deleted.

### 3. Process Flow Diagram (Large-Scale Recovery Scenario)

```mermaid
sequenceDiagram
    participant Client
    participant Gateway_MainProducer as "Gateway (Main Producer)"
    participant Gateway_LiveCapture as "Gateway (Live Capture)"
    participant MainLog as "Main Market Log"
    participant DeltaLog as "Delta Journal"

    Client->>+Gateway_MainProducer: Connection Request (lastEventId = N)
    Note over Gateway_LiveCapture, DeltaLog: Enable Delta Journal Mode
    Gateway_LiveCapture->>+DeltaLog: Write real-time data to delta journal
    deactivate Gateway_LiveCapture

    Gateway_MainProducer->>+MainLog: Read historical data (eventId > N)
    MainLog-->>-Gateway_MainProducer: Return historical data
    Gateway_MainProducer-->>Client: Push historical data

    Note over Gateway_MainProducer, MainLog: Main log reading complete
    deactivate MainLog

    Gateway_MainProducer->>+DeltaLog: Read data from delta journal
    DeltaLog-->>-Gateway_MainProducer: Return delta data
    Gateway_MainProducer-->>Client: Push delta recovery data

    Note over Gateway_MainProducer, DeltaLog: Delta journal reading complete
    deactivate DeltaLog

    Note over Gateway_LiveCapture: Switch to in-memory queue caching
    Gateway_LiveCapture->>Gateway_MainProducer: Cache last few real-time events
    Note over Gateway_MainProducer: Drain memory queue, switch data source
    Gateway_MainProducer-->>Client: Push last few recovery events

    loop Real-Time Mode
        Gateway_LiveCapture->>Gateway_MainProducer: Real-time event
        Gateway_MainProducer-->>Client: Push real-time event
    end
```

### 4. Conclusion

This solution effectively decouples the reliability of data distribution from the core matching business by introducing a dedicated Market Data Gateway. It provides a clear path for extension (e.g., upgrading from file logs to Kafka) and defines a clear contract for client-server interaction, robustly solving the needs of client reconnection and data recovery.
