# System Architecture and Order Flow

This document describes the architecture of the exchange core, focusing on the life cycle of an order command.

## Order Flow Diagram

The following diagram illustrates the journey of a command from the API to the final event consumers.

```mermaid
flowchart TD
    %% Client Nodes
    A[User API Client]
    B[ExchangeApi]

    %% Core Processing Subgraph
    subgraph Exchange Core [Disruptor RingBuffer]
        C{RingBuffer}
        D[GroupingProcessor]
        E[RiskEngine]
        F[MatchingEngineRouter]
        G[ResultsHandler]
    end

    %% Event Handling Nodes
    H[SimpleEventsProcessor]
    I{External Listeners}
    I1[CommandResult]
    I2[TradeEvent]
    I3[OrderBook Update]

    %% Links
    A -->|placeNewOrder| B;
    B -->|Publishes OrderCommand| C;
    C -->|Stage 1 Pre-processing| D;
    D --> E;
    E -->|Stage 2 Matching| F;
    F -->|Processes matching| F;
    F -->|Stage 3 Results| G;
    G -->|onEvent calls consumer accept| H;
    H -->|accept triggers| I;
    I --> I1;
    I --> I2;
    I --> I3;

    %% Styling for Visual Grouping
    classDef client fill:#cce5ff,stroke:#333,stroke-width:2px;
    classDef core fill:#fff2cc,stroke:#333,stroke-width:2px;
    classDef handling fill:#d4edda,stroke:#333,stroke-width:2px;

    class A,B client;
    class C,D,E,F,G core;
    class H,I,I1,I2,I3 handling;

    %% Clickable Links to Source Code
    click B "https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeApi.java" "Open ExchangeApi.java" _blank
    click D "https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/processors/GroupingProcessor.java" "Open GroupingProcessor.java" _blank
    click E "https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/processors/RiskEngine.java" "Open RiskEngine.java" _blank
    click F "https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/processors/MatchingEngineRouter.java" "Open MatchingEngineRouter.java" _blank
    click G "https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/processors/ResultsHandler.java" "Open ResultsHandler.java" _blank
    click H "https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/SimpleEventsProcessor.java" "Open SimpleEventsProcessor.java" _blank
```

## Component Descriptions

Here is a detailed breakdown of each component's role in the processing pipeline:

### Client
*   **User API Client**: Represents any external application or user script that interacts with the exchange. It initiates actions by sending commands, such as placing or canceling orders.

### [Exchange Core (Disruptor RingBuffer)](https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeCore.java)
This is the high-performance, low-latency core of the system, built on the LMAX Disruptor pattern. The entire processing pipeline is configured and orchestrated in the `ExchangeCore.java` class.

*   **ExchangeApi**: The public-facing gateway to the exchange. It provides a user-friendly API and is responsible for translating external calls (e.g., `placeNewOrder`) into the internal `OrderCommand` format. It then publishes these commands onto the `RingBuffer` for processing.

*   **RingBuffer**: The central data structure of the Disruptor framework. It's a pre-allocated circular buffer where `OrderCommand` objects live. All processing stages (processors) operate on the objects directly within this buffer, which enables lock-free, high-throughput communication between components.

*   **GroupingProcessor (Stage 1)**: 
    `GroupingProcessor` is the **first processor** in the Disruptor pipeline and serves as a critical **performance optimization component**. Its core idea is simple: **aggregate individual commands into batches and then pass the entire batch to the next processor.**

    This is like ordering at a fast-food restaurant. If the kitchen prepares one burger for every single customer order, it's inefficient. But if the cashier (`GroupingProcessor`) collects orders for 10 consecutive burgers and hands them to the kitchen (`RiskEngine`) all at once, the kitchen can operate like an assembly line, greatly improving efficiency.

    Let's delve into its mechanics:

    1.  **Objective: Increase Throughput**
        *   In ultra-low-latency systems, the fixed overhead of processing a single event (like method calls, cache misses, etc.) can become significant.
        *   By grouping commands, `GroupingProcessor` "amortizes" the processing cost over multiple commands. Downstream processors (like `RiskEngine`) only need to be woken up once to handle a batch of commands, rather than once for each command. This drastically reduces context switching and inter-processor communication overhead, thereby significantly boosting the system's overall throughput.

    2.  **How It Works**
        *   `GroupingProcessor` receives an `OrderCommand` from the `RingBuffer` in its `onEvent` method.
        *   It does **not** immediately pass this command on; instead, it holds onto it.
        *   It checks if the command is a "**trigger signal**." In `exchange-core`, this is typically a `GROUPING_FLUSH_SIGNAL` command or a special `endOfBatch` flag.
        *   When `GroupingProcessor` receives a trigger signal, or the number of held commands reaches a preset threshold (`groupingMaxBatchSize`), or the waiting time exceeds a certain threshold (`groupingMaxBatchDelayNs`), it marks the end of the current batch of accumulated commands and then updates its `Sequence`.
        *   This `Sequence` update triggers the `SequenceBarrier`, letting the downstream `RiskEngine` know: "All commands from the end of the last batch to this point are ready; you can start processing them."

    3.  **Batch Boundaries**
        There are two main conditions that trigger a batch "flush":
        *   **Size Threshold**: When the number of accumulated commands reaches `ExchangeConfiguration.ordersProcessing.groupingMaxBatchSize`, a flush is triggered.
        *   **Time Threshold**: To prevent commands from being indefinitely delayed under high load, the system periodically sends a `GROUPING_FLUSH_SIGNAL`. This signal acts like an alarm clock, telling the `GroupingProcessor`: "No matter how many commands you've gathered, package them up and send them off immediately." This ensures an upper bound on latency.

    **Summary**
    `GroupingProcessor` is a classic **batch processing** optimization. It sacrifices the **lowest possible latency** for a single command (as it has to wait to be grouped) in exchange for **higher overall system throughput**. In scenarios like financial trading, which require handling a massive volume of concurrent requests, this trade-off is very common and effective. It forms Stage 1 along with `RiskEngine`, preparing batches of pre-processed commands for the subsequent matching stage.

*   **RiskEngine (Stage 1)**: The second processor, responsible for pre-trade risk management and user account state. It's a stateful component that maintains all user profiles, balances, and positions. When it receives a `PLACE_ORDER` command, it checks if the user has sufficient funds or margin to cover the order. It will reject any command that fails these risk checks. It also handles administrative tasks like balance adjustments and user creation.

*   **MatchingEngineRouter (Stage 2)**: The third processor and the heart of the matching logic. It takes commands that have been cleared by the `RiskEngine` and routes them to the appropriate `IOrderBook` instance based on the command's symbol. It executes the core matching algorithm, which results in trades, rejections, or modifications to the order book. The outcomes are attached to the `OrderCommand` as a chain of `MatcherTradeEvent` objects. It is also responsible for generating L2 market data snapshots.

*   **ResultsHandler (Stage 3)**: The final processor in the Disruptor pipeline. Its role is simple but crucial: it takes the fully processed `OrderCommand`—now enriched with a final result code and a chain of matcher events—and passes it to the designated downstream event consumer.

### Event Handling
*   **SimpleEventsProcessor**: This component acts as the primary downstream consumer. It receives the processed `OrderCommand` from the `ResultsHandler` and translates the internal, complex data structures into clean, discrete events suitable for external systems. It "unpacks" the command to produce `CommandResult` (the high-level outcome), `TradeEvent` (detailed trade information), and `OrderBook` (market data updates).

*   **External Listeners**: This represents the final destination for the events generated by the `SimpleEventsProcessor`. These are the client-side applications, databases, UI frontends, or analytics systems that subscribe to the event stream to stay synchronized with the state of the exchange.

---

## API Usage: `ExchangeApi` Deep Dive

The `ExchangeApi` class serves as the entry point and facade for the entire trading core. It provides a clear, user-friendly interface for external clients, abstracting away the complexities of the underlying Disruptor framework. Its key responsibilities include:

1.  **API Facade**: It hides the complexity of interacting with the Disruptor `RingBuffer`. Developers call simple methods like `submitCommand(ApiPlaceOrder cmd)` without needing to understand the internal mechanics.
2.  **Command Translation & Publishing**: Its core duty is to translate `ApiCommand` objects into the internal `OrderCommand` format. It uses a predefined `EventTranslator` to copy fields into a pre-allocated `OrderCommand` on the `RingBuffer` and then publishes it, making it visible to the processing pipeline.
3.  **Asynchronous Result Handling**: For async calls, `ExchangeApi` maintains a map of `promises`. It stores a `CompletableFuture` callback against a command's sequence number. When the command is fully processed, the `ResultsHandler` invokes `ExchangeApi.processResult()`, which finds the corresponding callback and completes the future, delivering the result to the original caller.

#### `submitCommandAsync` vs `submitCommandAsyncFullResponse`

The key difference lies in the amount of information returned in the `CompletableFuture`:

*   **`submitCommandAsync`**:
    *   **Returns**: `CompletableFuture<CommandResultCode>`
    *   **Content**: Only the final status code (`SUCCESS`, `RISK_NSF`, etc.).
    *   **Use Case**: Ideal when you only need to know if an operation succeeded or failed, without needing the details of its side effects.

*   **`submitCommandAsyncFullResponse`**:
    *   **Returns**: `CompletableFuture<OrderCommand>`
    *   **Content**: The entire, fully-processed `OrderCommand` object, which includes the `resultCode`, a chain of `MatcherTradeEvent`s (trades), and potentially `L2MarketData`.
    *   **Use Case**: Essential when you need the full details of the operation's outcome, such as the average fill price and trade-by-trade specifics of a market order.

#### Asynchronous Usage Pattern

The standard way to interact with the API asynchronously is:

1.  **Call an `async` method**: `CompletableFuture<OrderCommand> future = exchangeApi.submitCommandAsyncFullResponse(placeOrderCmd);`
2.  **Process the `Future`**:
    *   **Blocking Wait (for tests)**: `OrderCommand result = future.join();`
    *   **Non-Blocking Callback (recommended)**: `future.thenAccept(result -> { /* process result here */ });`

---

## Disruptor Pipeline Orchestration

The `RingBuffer` itself is just a high-performance, lock-free circular queue responsible for storing and passing data (in this case, `OrderCommand` objects). **It does not directly "orchestrate" the stages; instead, this is achieved through a mechanism called a "Dependency Barrier."**

This orchestration process is defined in the `ExchangeCore` constructor, which can be thought of as building a "dependency graph." Let's break it down:

1.  **Core Concepts: Sequence and Barrier**
    *   **Sequence**: Each processor has its own `Sequence` object. This acts as a "counter" for the position (sequence number) in the `RingBuffer` that the processor has currently handled.
    *   **SequenceBarrier**: This is a barrier. Before processing the next item, a processor must wait for the `Sequence` of all its prerequisite processors to advance past that item's position. This barrier ensures that a processor does not handle data that has not yet been processed by its dependencies.

2.  **Orchestrating Stages 1, 2, and 3**
    In `ExchangeCore.java`, you will see code like this (this is the standard LMAX Disruptor setup pattern):
    ```java
    // 1. Create an initial barrier from the RingBuffer, which all Stage 1 processors depend on.
    SequenceBarrier barrier1 = ringBuffer.newBarrier();

    // 2. Create Stage 1 processors (Grouping, Risk), both waiting for barrier1.
    //    - GroupingProcessor(barrier1)
    //    - RiskEngine(barrier1)
    //    These two processors can execute in parallel as they have no dependency on each other, only on the raw data from the RingBuffer.

    // 3. Create a Stage 2 barrier that waits for all Stage 1 processors to complete.
    //    This barrier will track the Sequences of GroupingProcessor and RiskEngine.
    SequenceBarrier barrier2 = ringBuffer.newBarrier(
        groupingProcessor.getSequence(), 
        riskEngine.getSequence()
    );

    // 4. Create the Stage 2 processor (MatchingEngine), which waits for barrier2.
    //    - MatchingEngineRouter(barrier2)
    //    This means MatchingEngineRouter must wait until both GroupingProcessor and RiskEngine
    //    have finished processing the same OrderCommand before it can begin.

    // 5. Create a Stage 3 barrier that waits for the Stage 2 processor to complete.
    SequenceBarrier barrier3 = ringBuffer.newBarrier(
        matchingEngineRouter.getSequence()
    );

    // 6. Create the Stage 3 processor (ResultsHandler), which waits for barrier3.
    //    - ResultsHandler(barrier3)
    ```

3.  **Workflow (Example with one `OrderCommand`)**
    *   **Publish**: `ExchangeApi` publishes an `OrderCommand` to sequence number `N` on the `RingBuffer`.
    *   **Stage 1**:
        *   `GroupingProcessor` and `RiskEngine` are both waiting for `barrier1`. Once sequence `N` is available, they can both start processing the command in `RingBuffer[N]`.
        *   After they each finish processing, they update their own `Sequence` to `N`.
    *   **Stage 2**:
        *   `MatchingEngineRouter` waits for `barrier2`. `barrier2` checks the `Sequence` of both `GroupingProcessor` and `RiskEngine`. Only when both sequences have reached or surpassed `N` does `barrier2` give the green light.
        *   Once cleared, `MatchingEngineRouter` processes the command at `RingBuffer[N]`. After completion, it updates its `Sequence` to `N`.
    *   **Stage 3**:
        *   `ResultsHandler` waits for `barrier3`. `barrier3` checks the `Sequence` of `MatchingEngineRouter`. Once it reaches `N`, `barrier3` gives the green light.
        *   `ResultsHandler` begins processing, completing all operations for this command.

**Summary**
The `RingBuffer` is like a physical assembly line conveyor belt, while **orchestration is implemented via the `SequenceBarrier`**. Each `SequenceBarrier` acts like a "checkpoint" on the assembly line, ensuring that a part (`OrderCommand`) can only move to the next station after all previous stations (dependent processors) have completed their work on it.

In this way, Disruptor elegantly defines the dependencies and execution order among processors, achieving an efficient, lock-free, parallel, and serial processing flow.
