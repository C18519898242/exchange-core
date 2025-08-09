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
        
        subgraph Stage 1 [Parallel Pre-processing]
            direction LR
            D[GroupingProcessor]
            E[RiskEngine]
        end

        F[MatchingEngineRouter]
        G[ResultsHandler]
    end

    %% Event Handling Nodes
    H[SimpleEventsProcessor]
    I{External Listeners}
    I1[CommandResult]
    I2[TradeEvent]
    I3[OrderBook Update]

    %% Connections
    A -->|placeNewOrder| B;
    B -->|Publishes OrderCommand| C;
    C --> D;
    C --> E;
    D -->|Defines batch boundary| F;
    E -->|Checks & holds funds| F;
    F -->|Stage 2 Matching| F;
    F -->|Stage 3 Results| G;
    G -->|onEvent calls consumer accept| H;
    H -->|accept triggers| I;
    I --> I1;
    I --> I2;
    I --> I3;

    %% Style Definitions
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

*   **GroupingProcessor (Stage 1, Parallel)**: As **one of the parallel processors** in Stage 1, its primary function is to batch incoming commands into groups. This is a performance optimization that improves throughput. It is not concerned with the business content of commands but only defines the "batch" boundaries for downstream processors.

*   **RiskEngine (Stage 1, Parallel)**: As the **other parallel processor** in Stage 1, it is responsible for pre-trade risk management and user account state. It is a stateful component that inspects every command within a batch. When it receives a `PLACE_ORDER` command, it checks if the user has sufficient funds or margin to cover the order and rejects any command that fails these checks.

*   **MatchingEngineRouter (Stage 2)**: The **Stage 2** processor and the heart of the matching logic. It must wait for the **same command** to be processed by both `GroupingProcessor` and `RiskEngine` before it can begin. It takes commands that have passed risk checks and routes them to the appropriate `IOrderBook` instance for matching. The outcomes are attached to the `OrderCommand` as a chain of `MatcherTradeEvent` objects.

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

---

## Component Deep Dive: GroupingProcessor

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
    There are two main conditions that trigger a batch "flush", both implemented within the `processEvents()` method of `GroupingProcessor.java`:

    *   **Size Threshold**: While processing events, if the number of accumulated commands in a batch (`msgsInGroup`) reaches the limit (`msgsInGroupLimit`), the processor forces a switch to the next batch.
        ```java
        if (msgsInGroup >= msgsInGroupLimit && cmd.command != OrderCommandType.PERSIST_STATE_RISK) {
            groupCounter++;
            msgsInGroup = 0;
        }
        ```

    *   **Time Threshold**: When there are no new events in the `RingBuffer` and the processor is idle, it checks an internal timer. If the current time has exceeded the maximum waiting time for the batch (`groupLastNs`), it forces the current batch to end, preventing excessive command latency.
        ```java
        } else {
            // Executes when the processor is idle
            final long t = System.nanoTime();
            if (msgsInGroup > 0 && t > groupLastNs) {
                // Switch group if time expired and batch is not empty
                groupCounter++;
                msgsInGroup = 0;
            }
        }
        ```

**Summary**
`GroupingProcessor` is a classic **batch processing** optimization. It sacrifices the **lowest possible latency** for a single command (as it has to wait to be grouped) in exchange for **higher overall system throughput**. In scenarios like financial trading, which require handling a massive volume of concurrent requests, this trade-off is very common and effective. It forms Stage 1 along with `RiskEngine`, preparing batches of pre-processed commands for the subsequent matching stage.

---

## Component Deep Dive: RiskEngine

The `RiskEngine` is the **second gate** in the trading pipeline and the **first true business logic gate**. It is a **stateful** component whose core responsibility is **pre-trade risk checks and account state management**. Think of it as a bank teller who must verify your identity, check if your account is active, and ensure you have sufficient funds before processing a transfer request (placing an order).

Its work is divided into two main parts: **pre-processing (Hold)** and **post-processing (Release)**, corresponding to the `preProcessCommand` and `handlerRiskRelease` methods.

### Command Types Handled by `RiskEngine`

The `RiskEngine` is a **mandatory stop** for almost all commands before they reach the matching engine. It uses a large `switch` statement (in the `preProcessCommand` method) to differentiate and apply logic for various command types:

1.  **`PLACE_ORDER`**: This is the most complex and critical logic.
    *   **Checks**: Verifies the user exists, the symbol is valid, and the order quantity/price are legal.
    *   **Calculation**: Computes the required funds (margin) to hold based on the order's side, price, and quantity.
    *   **Hold**: Transfers the required funds from the user's available balance (`balance`) to the held amount (`heldAmount`).
    *   **Rejection**: If the available balance is insufficient, it immediately sets `resultCode = RISK_NSF` (Not Sufficient Funds) and terminates the command's processing.

2.  **`CANCEL_ORDER`**:
    *   **Checks**: Verifies the order exists and belongs to the user.
    *   **Note**: It does **not** immediately release the held funds. This is because it doesn't know if the order was partially filled at the moment of cancellation. The release of funds must await the final result from the matching engine.

3.  **`MOVE_ORDER`**:
    *   **Checks**: Same as `CANCEL_ORDER`.
    *   **Processing**: It handles the old order like a `CANCEL_ORDER` and the new-priced order like a `PLACE_ORDER`, but this is all at the "pre-processing" level. The actual fund changes depend on the matching result.

4.  **`ADJUST_BALANCE`**:
    *   **Checks**: Verifies the user exists.
    *   **Processing**: Directly modifies the user's `balance`. This is a purely administrative action that does not proceed to the matching engine.

#### A Note on "Market Buy by Quantity"

The `RiskEngine` is designed to strictly differentiate between "by amount" and "by quantity" market orders. To understand the challenges, design trade-offs, and the final best-practice solution for the complex "Market Buy by Quantity" feature, please refer to the dedicated deep-dive document:

*   **[Deep Dive: Designing and Implementing Market Buy by Quantity](./DEEP_DIVE_MARKET_BUY_BY_QUANTITY.md)**

### Core Data Structures

All of `RiskEngine`'s logic revolves around two core, in-memory `Map`s (specifically `LongObjectHashMap` for performance):

1.  **`users` (`LongObjectHashMap<UserProfile>`)**:
    *   **Key**: `uid` (User ID)
    *   **Value**: A `UserProfile` object, containing all information for that user.

2.  **`accounts` (`IntObjectHashMap<UserCurrencyAccount>`)**:
    *   This is a `Map` inside the `UserProfile`.
    *   **Key**: `currency` code
    *   **Value**: A `UserCurrencyAccount` object, which stores the user's financial information for a **specific currency**, including:
        *   `balance`: The total balance.
        *   `heldAmount`: The amount frozen for open orders.
        *   The **available balance** is calculated as `balance - heldAmount`.

### Risk Validation Failure (The "Short-Circuit" Mechanism)

When a `RiskEngine` validation fails, a **"short-circuit"** operation occurs. This is a critical performance and safety feature of the trading system.

Here is the process:

1.  **Set Rejection Code**: In the `preProcessCommand` method, if a `PLACE_ORDER` command fails validation for any reason (most commonly, insufficient funds or NSF), the `RiskEngine` immediately sets the `resultCode` on the `OrderCommand` object to a specific failure code, such as `RISK_NSF`.

2.  **Skip Matching**: When the `MatchingEngineRouter` receives this command, it checks the `resultCode`. Seeing that the command has already been marked as failed, it **completely skips** all matching logic for it and passes the command "as-is" to the next stage.

3.  **Maintain Process Integrity**: Even though the command is rejected, it **still completes its journey through the entire pipeline** instead of being removed midway. This ensures the Disruptor's `Sequence` mechanism is not disrupted and all processors remain synchronized.

4.  **Provide Clear Feedback**: Ultimately, the `ResultsHandler` passes on the command with its failure code, and the client receives a precise reason for the failure (e.g., "insufficient funds") rather than a generic "processing error."

This "short-circuit" mechanism is an elegant design for high-performance systems, as it quickly rejects invalid operations without compromising the integrity and consistency of the processing pipeline.

### Summary: The Essence of `RiskEngine`

The `RiskEngine` is essentially an **in-memory, high-performance state machine for accounts and positions**.

*   Through its **hold mechanism (`heldAmount`)**, it ensures that a user's funds are safely reserved before an order enters the "black box" of the matching engine.
*   Through its **two-stage processing (`preProcessCommand` and `handlerRiskRelease`)**, it guarantees that no matter the matching outcome (full fill, partial fill, or no fill), the user's final account state remains perfectly consistent with the result.

This design makes the `RiskEngine` the **cornerstone of financial security** for the entire trading system.

---

## Component Deep Dive: MatchingEngineRouter

The `MatchingEngineRouter` is the **third gate** in the Disruptor pipeline and the "heart" of the entire trading system, responsible for executing the core function of **order matching**.

If the `RiskEngine` is the "treasurer," then the `MatchingEngineRouter` is the "trader." Its responsibilities sound simple but are implemented with great subtlety.

### Core Responsibilities: Routing and Matching

The name `MatchingEngineRouter` encompasses its two core duties:

1.  **Router**:
    *   A system can have hundreds or thousands of trading pairs (BTC/USDT, ETH/USDT, ...). Each pair has its own independent **Order Book**.
    *   The primary task of the `MatchingEngineRouter` is to act like a traffic controller, **dispatching** an order to the correct order book based on the `symbolId` in the `OrderCommand`.
    *   It maintains an internal `Map` where the `key` is the `symbolId` and the `value` is the corresponding `IOrderBook` instance.

2.  **Matching Engine**:
    *   Once an order is dispatched to a specific `IOrderBook` instance, the real matching logic begins.
    *   An `IOrderBook` is a data structure that maintains all outstanding buy and sell orders for that symbol, sorted by price-time priority.
    *   **Matching Process**:
        *   **Is the new order a buy?**: It looks for the lowest-priced sell order in the book. If the new order's bid price >= the lowest ask price, a match occurs! It continues to match against the next-lowest sell order until the order is fully filled or no more suitable counter-orders can be found.
        *   **Is the new order a sell?**: It looks for the highest-priced buy order. If the new order's ask price <= the highest bid price, a match occurs! It then continues to the next-highest buy order.
        *   **If no counter-order is found?**: The new order is placed on the order book, becoming a new Maker Order, waiting for another order to match with it.

### Key Interactions of `MatchingEngineRouter`

1.  **Receiving Input**:
    *   It receives `OrderCommand`s that have already passed through the `RiskEngine` (risk checks) and `GroupingProcessor` (batching).
    *   **Important**: It must wait for the **same** `OrderCommand` to be processed by both preceding processors before it can start. This is guaranteed by the Disruptor's `SequenceBarrier`.

2.  **Processing Logic**:
    *   **Check for "Short-Circuit" Signal**: Before doing anything, it checks the `resultCode` of the `OrderCommand`. If the `RiskEngine` has already marked the order as failed (e.g., `RISK_NSF`), the `MatchingEngineRouter` will **completely skip** all matching logic and pass the failed command on as-is.
    *   **Execute Matching**: For valid orders, it calls the `match(OrderCommand)` method of the corresponding `IOrderBook`.
    *   **Record Results**: During matching, the `IOrderBook` generates a series of **`MatcherTradeEvent`s**. These events detail every aspect of each match (whose order was filled, price, quantity, etc.). The `MatchingEngineRouter` links these events together like a linked list and attaches them to the `OrderCommand`.

3.  **Producing Output**:
    *   After processing, the `MatchingEngineRouter` passes the "enriched" `OrderCommand` (now containing the matching results) to the next and final stage of the pipeline: the `ResultsHandler`.

### `IOrderBook` Implementations

`exchange-core` provides two implementations of `IOrderBook`, which have vastly different design philosophies and performance characteristics. To fully understand the internal workings, data structures, and performance trade-offs of these two implementations, please refer to the dedicated deep-dive document:

*   **[Deep Dive: The Two Implementations of the Order Book](./DEEP_DIVE_ORDERBOOK.md)**

In short:
*   **`OrderBookNaiveImpl`**: Uses a standard `TreeMap`, is easy to understand, and is suitable for getting started and for testing.
*   **`OrderBookDirectImpl`**: Uses a custom linked list and radix tree, combined with object pooling, and is built for extreme performance, making it the preferred choice for production environments.

### Summary

The `MatchingEngineRouter` is the central hub connecting **risk control** and **trade execution**.

*   It acts as a **dispatcher**, ensuring every order finds its correct arena (the order book).
*   It drives the **executor** (`IOrderBook`) to complete the core value exchange (order matching).
*   It is also a **recorder**, clearly documenting every detail of a match in `MatcherTradeEvent`s, providing an immutable basis for downstream clearing and settlement.

Its design embodies the Single Responsibility Principle: it only cares about "matching" and does it to perfection. It is not concerned with user balances (that's the `RiskEngine`'s job) or how the final result is communicated to the user (that's the `ResultsHandler`'s job).
