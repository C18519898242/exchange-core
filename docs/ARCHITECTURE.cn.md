# 系统架构和订单流

本文档描述了交易核心的架构，重点关注订单命令的生命周期。

## 订单流图

下图说明了命令从 API 到最终事件消费者的流转过程。

```mermaid
flowchart TD
    %% Client Nodes
    A[用户 API 客户端]
    B[ExchangeApi]

    %% Core Processing Subgraph
    subgraph 交易核心 [Disruptor RingBuffer]
        C{RingBuffer}
        
        subgraph 阶段 1 [并行预处理]
            direction LR
            D[GroupingProcessor]
            E[RiskEngine]
        end

        F[MatchingEngineRouter]
        G[ResultsHandler]
    end

    %% Event Handling Nodes
    H[SimpleEventsProcessor]
    I{外部监听器}
    I1[命令结果]
    I2[交易事件]
    I3[订单簿更新]

    %% Connections
    A -->|placeNewOrder| B;
    B -->|发布 OrderCommand| C;
    C --> D;
    C --> E;
    D -->|定义批次边界| F;
    E -->|检查并冻结资金| F;
    F -->|阶段 2 撮合| F;
    F -->|阶段 3 结果| G;
    G -->|onEvent 调用 consumer accept| H;
    H -->|accept 触发| I;
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

## 组件描述

以下是处理流水线中每个组件角色的详细分解：

### 客户端
*   **用户 API 客户端**: 代表与交易所交互的任何外部应用程序或用户脚本。它通过发送命令（如放置或取消订单）来发起操作。

### [交易核心 (Disruptor RingBuffer)](https://github.com/C18519898242/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeCore.java)
这是系统的高性能、低延迟核心，基于 LMAX Disruptor 模式构建。整个处理流水线在 `ExchangeCore.java` 类中进行配置和编排。

*   **ExchangeApi**: 面向公众的交易所网关。它提供了一个用户友好的 API，并负责将外部调用（例如 `placeNewOrder`）转换为内部的 `OrderCommand` 格式。然后，它将这些命令发布到 `RingBuffer` 上进行处理。

*   **RingBuffer**: Disruptor 框架的核心数据结构。它是一个预先分配的环形缓冲区，`OrderCommand` 对象存活于此。所有处理阶段（处理器）都直接操作此缓冲区中的对象，从而实现了组件之间无锁、高吞吐量的通信。

*   **GroupingProcessor (阶段 1, 并行)**: 作为阶段1的**并行处理器之一**，其主要功能是将传入的命令分组成批次。这是一种性能优化，通过减少处理每个命令的开销来提高吞吐量。它不关心命令的业务内容，只为下游定义“批次”的边界。

*   **RiskEngine (阶段 1, 并行)**: 作为阶段1的**另一个并行处理器**，负责交易前风险管理和用户账户状态。它是一个有状态的组件，对批次内的每个命令进行检查。当收到 `PLACE_ORDER` 命令时，它会检查用户是否有足够的资金或保证金来覆盖该订单。它将拒绝任何未通过这些风险检查的命令。

*   **MatchingEngineRouter (阶段 2)**: **第二阶段**的处理器，是撮合逻辑的核心。它必须等待**同一个命令**被 `GroupingProcessor` 和 `RiskEngine` 都处理完毕后才能开始。它接收已通过风险检查的命令，并根据交易对将其路由到相应的 `IOrderBook` 实例执行撮合。结果作为 `MatcherTradeEvent` 对象链附加到 `OrderCommand` 上。

*   **ResultsHandler (阶段 3)**: Disruptor 流水线中的最后一个处理器。其作用简单但至关重要：它接收完全处理过的 `OrderCommand`——现在已富含最终结果代码和撮合事件链——并将其传递给指定的下游事件消费者。

### 事件处理
*   **SimpleEventsProcessor**: 该组件充当主要的下游消费者。它从 `ResultsHandler` 接收处理过的 `OrderCommand`，并将内部复杂的的数据结构转换为适用于外部系统的干净、离散的事件。它“解包”命令以产生 `CommandResult`（高级别结果）、`TradeEvent`（详细交易信息）和 `OrderBook`（市场数据更新）。

*   **外部监听器**: 这代表了 `SimpleEventsProcessor` 生成的事件的最终目的地。这些是订阅事件流以与交易所状态保持同步的客户端应用程序、数据库、UI 前端或分析系统。

---

## API 用法: `ExchangeApi` 深度解析

`ExchangeApi` 类是整个交易核心的入口和门面（Facade）。它为外部客户端提供了一套清晰、易于使用的接口，同时将底层 Disruptor 框架的复杂性抽象出来。其核心职责包括：

1.  **API 门面**: 它封装了与 Disruptor `RingBuffer` 交互的复杂性。开发者只需调用如 `submitCommand(ApiPlaceOrder cmd)` 这样的简单方法，而无需理解内部机制。
2.  **命令翻译与发布**: 它的核心任务是将 `ApiCommand` 对象翻译成内部的 `OrderCommand` 格式。它使用预定义的 `EventTranslator` 将字段复制到 `RingBuffer` 上的一个预分配 `OrderCommand` 对象中，然后发布它，使其对处理流水线可见。
3.  **异步结果处理**: 对于异步调用，`ExchangeApi` 维护一个 `promises` 映射。它根据命令的序列号存储一个 `CompletableFuture` 的回调。当命令处理完毕，`ResultsHandler` 会调用 `ExchangeApi.processResult()`，该方法会找到对应的回调并完成 `Future`，从而将结果传递给原始调用者。

#### `submitCommandAsync` 与 `submitCommandAsyncFullResponse` 的区别

核心区别在于 `CompletableFuture` 返回的信息量：

*   **`submitCommandAsync`**:
    *   **返回**: `CompletableFuture<CommandResultCode>`
    *   **内容**: 仅包含最终的状态码（如 `SUCCESS`, `RISK_NSF` 等）。
    *   **应用场景**: 当您只关心操作是否成功，而不需要其副作用的细节时，这是理想的选择。

*   **`submitCommandAsyncFullResponse`**:
    *   **返回**: `CompletableFuture<OrderCommand>`
    *   **内容**: 整个处理完毕的 `OrderCommand` 对象，包含 `resultCode`、`MatcherTradeEvent` 链（交易明细）以及可能的 `L2MarketData`。
    *   **应用场景**: 当您需要操作结果的全部细节时（例如市价单的平均成交价和每笔成交详情），此方法是必需的。

#### 异步使用模式

与 API 进行异步交互的标准方式是：

1.  **调用 `async` 方法**: `CompletableFuture<OrderCommand> future = exchangeApi.submitCommandAsyncFullResponse(placeOrderCmd);`
2.  **处理 `Future`**:
    *   **阻塞式等待 (用于测试)**: `OrderCommand result = future.join();`
    *   **非阻塞式回调 (推荐)**: `future.thenAccept(result -> { /* 在此处理结果 */ });`

---

## Disruptor 流水线编排

`RingBuffer` 本身只是一个高性能的、无锁的环形队列，它负责存储和传递数据（在这里是 `OrderCommand` 对象）。**它本身并不直接“编排”各个 Stage，而是通过一种叫做“依赖屏障”（Dependency Barrier）的机制来实现的。**

这个编排过程是在 `ExchangeCore` 的构造函数中定义的，我们可以把它想象成一个“依赖关系图”的构建过程。让我们来梳理一下：

1.  **基本概念：Sequence 和 Barrier**
    *   **Sequence**: 每个处理器（Processor）都有一个自己的 `Sequence` 对象。这可以看作是这个处理器当前处理到了 `RingBuffer` 中的哪个位置（序列号）的“计数器”。
    *   **SequenceBarrier**: 这是一个“屏障”。一个处理器在处理下一个数据之前，必须等待它所依赖的所有前置处理器的 `Sequence` 都越过这个数据的位置。这个屏障确保了处理器不会处理尚未被其前置依赖处理过的数据。

2.  **编排 Stage 1, 2, 3 的过程**
    在 `ExchangeCore.java` 中，您会看到类似这样的代码（这是 LMAX Disruptor 的标准设置模式）：
    ```java
    // 1. 从 RingBuffer 创建一个初始屏障，所有第一阶段的处理器都依赖它
    SequenceBarrier barrier1 = ringBuffer.newBarrier();

    // 2. 创建 Stage 1 的处理器 (Grouping, Risk)，它们都等待 barrier1
    //    - GroupingProcessor(barrier1)
    //    - RiskEngine(barrier1)
    //    这两个处理器可以并行执行，因为它们没有相互依赖，只依赖 RingBuffer 的原始数据。

    // 3. 创建 Stage 2 的屏障，它等待 Stage 1 的所有处理器完成
    //    这个屏障会跟踪 GroupingProcessor 和 RiskEngine 的 Sequence
    SequenceBarrier barrier2 = ringBuffer.newBarrier(
        groupingProcessor.getSequence(), 
        riskEngine.getSequence()
    );

    // 4. 创建 Stage 2 的处理器 (MatchingEngine)，它等待 barrier2
    //    - MatchingEngineRouter(barrier2)
    //    这意味着 MatchingEngineRouter 必须等到 GroupingProcessor 和 RiskEngine 
    //    都处理完同一个 OrderCommand 后，才能开始处理它。

    // 5. 创建 Stage 3 的屏障，它等待 Stage 2 的处理器完成
    SequenceBarrier barrier3 = ringBuffer.newBarrier(
        matchingEngineRouter.getSequence()
    );

    // 6. 创建 Stage 3 的处理器 (ResultsHandler)，它等待 barrier3
    //    - ResultsHandler(barrier3)
    ```

3.  **工作流程（以一个 `OrderCommand` 为例）**
    *   **发布**: `ExchangeApi` 将一个 `OrderCommand` 发布到 `RingBuffer` 的序列号 `N`。
    *   **Stage 1**:
        *   `GroupingProcessor` 和 `RiskEngine` 都在等待 `barrier1`。一旦序列号 `N` 可用，它们俩都可以开始处理 `RingBuffer[N]` 里的这个命令。
        *   它们各自完成处理后，会更新自己的 `Sequence` 到 `N`。
    *   **Stage 2**:
        *   `MatchingEngineRouter` 在等待 `barrier2`。`barrier2` 会检查 `GroupingProcessor` 和 `RiskEngine` 的 `Sequence`。只有当这两个 `Sequence` 都达到或超过 `N` 时，`barrier2` 才会放行。
        *   `barrier2` 放行后，`MatchingEngineRouter` 开始处理 `RingBuffer[N]` 的命令。处理完成后，它也更新自己的 `Sequence` 到 `N`。
    *   **Stage 3**:
        *   `ResultsHandler` 在等待 `barrier3`。`barrier3` 检查 `MatchingEngineRouter` 的 `Sequence`。一旦它达到 `N`，`barrier3` 就放行。
        *   `ResultsHandler` 开始处理，并最终完成对这个命令的所有操作。

**总结**
`RingBuffer` 就像一条物理的流水线传送带，而**编排是通过 `SequenceBarrier` 实现的**。每个 `SequenceBarrier` 都像流水线上的一个“检查点”，它确保只有在所有前序工位（依赖的处理器）都完成了对某个零件（`OrderCommand`）的操作后，这个零件才能流转到下一个工位。

通过这种方式，Disruptor 巧妙地定义了处理器之间的依赖关系和执行顺序，实现了高效的、无锁的并行和串行处理流程。

---

## GroupingProcessor 组件深度解析

`GroupingProcessor` 是 Disruptor 流水线中的**第一个处理器**，它是一个非常关键的**性能优化组件**。它的核心思想很简单：**将单个的命令聚合成批次（Batches），然后将整个批次传递给下一个处理器**。

这就像在快餐店点餐，如果每个顾客点一个汉堡，厨师就做一个汉堡，效率会很低。但如果收银员（`GroupingProcessor`）把连续 10 个汉堡的订单收集起来，一次性交给后厨（`RiskEngine`），后厨就可以流水线作业，大大提高效率。

让我们深入了解它的工作机制：

1.  **目的：提升吞吐量 (Throughput)**
    *   在超低延迟的系统中，处理单个事件的固定开销（比如方法调用、缓存未命中等）可能会变得非常显著。
    *   通过将命令分组，`GroupingProcessor` 将多个命令的处理成本“摊销”了。下游的处理器（如 `RiskEngine`）只需要被唤醒一次就可以处理一批命令，而不是每个命令都被唤醒一次。这极大地减少了上下文切换和处理器间的通信开销，从而显著提升了系统的总吞吐量。

2.  **工作原理**
    *   `GroupingProcessor` 在 `onEvent` 方法中接收从 `RingBuffer` 传来的 `OrderCommand`。
    *   它并**不立即**将这个命令传递下去，而是先把它“扣留”下来。
    *   它会检查这个命令是否是一个“**触发信号**”。在 `exchange-core` 中，这个信号通常是 `GROUPING_FLUSH_SIGNAL` 命令，或者是一个特殊的 `endOfBatch` 标志。
    *   当 `GroupingProcessor` 收到一个触发信号，或者它“扣留”的命令数量达到了一个预设的阈值（`groupingMaxBatchSize`），或者等待时间超过了某个阈值（`groupingMaxBatchDelayNs`），它就会把当前积累的所有命令作为一个“批次”的结束，然后更新自己的 `Sequence`。
    *   这个 `Sequence` 的更新会触发 `SequenceBarrier`，从而让下游的 `RiskEngine` 知道：“从上一个批次的结束点到现在这个点，所有的命令都准备好了，你可以开始处理了。”

3.  **分组的边界**
    分组的触发条件主要有两种，它们都在 `GroupingProcessor.java` 的 `processEvents()` 方法中实现：

    *   **数量阈值**: 在处理事件时，如果一个批次内累积的命令数 (`msgsInGroup`) 达到上限 (`msgsInGroupLimit`)，处理器会强制切换到下一个批次。
        ```java
        if (msgsInGroup >= msgsInGroupLimit && cmd.command != OrderCommandType.PERSIST_STATE_RISK) {
            groupCounter++;
            msgsInGroup = 0;
        }
        ```

    *   **时间阈值**: 当 `RingBuffer` 中没有新事件，处理器处于等待状态时，它会检查内部计时器。如果当前时间已超过本批次的最长等待时间 (`groupLastNs`)，它会强制结束当前批次，以避免命令被过度延迟。
        ```java
        } else {
            // 当处理器空闲时执行
            final long t = System.nanoTime();
            if (msgsInGroup > 0 && t > groupLastNs) {
                // 如果等待时间超时且批次不为空，则切换批次
                groupCounter++;
                msgsInGroup = 0;
            }
        }
        ```

**总结**
`GroupingProcessor` 是一个典型的“**批处理**”优化。它牺牲了单个命令的**最低延迟**（因为命令需要等待成组），来换取整个系统**更高的总吞t量**。在金融交易这种需要处理海量并发请求的场景下，这种权衡是非常常见且有效的。它和 `RiskEngine` 一起构成了 Stage 1，为后续的撮合阶段准备好了一批批经过预处理的命令。

---

## RiskEngine 组件深度解析

`RiskEngine` 是交易流水线中的**第二道关卡**，也是**第一道真正的业务逻辑关卡**。它是一个**有状态**的组件，核心职责是**交易前的风险检查和账户状态管理**。可以把它想象成银行柜台，在处理转账请求（下单）之前，必须先验证你的身份、检查账户是否正常、余额是否足够。

它的工作可以分为两个主要部分：**预处理（Hold）** 和 **后处理（Release）**，分别对应 `preProcessCommand` 和 `handlerRiskRelease` 两个方法。

### `RiskEngine` 处理的命令类型

`RiskEngine` 几乎是所有命令进入撮合引擎前的**必经之路**。它通过一个巨大的 `switch` 语句（在 `preProcessCommand` 方法中）来区分不同的命令类型并执行相应的逻辑：

1.  **`PLACE_ORDER` (下单)**: 这是最复杂、最核心的逻辑。
    *   **检查**: 用户是否存在、品种是否有效、订单数量和价格是否合法。
    *   **计算**: 根据买卖方向、价格和数量，计算需要冻结的资金（保证金）。
    *   **冻结**: 从用户账户的可用余额 (`balance`) 中，将所需资金转移到持有余额 (`heldAmount`)。
    *   **拒绝**: 如果可用余额不足，直接设置 `resultCode = RISK_NSF` (Not Sufficient Funds)，命令处理终止。

2.  **`CANCEL_ORDER` (取消订单)**:
    *   **检查**: 订单是否存在、是否属于该用户。
    *   **注意**: 此时**不会**立即解冻资金。因为它不知道这个订单在被取消的瞬间是否已经部分成交。资金的解冻必须等待撮合引擎的最终结果。

3.  **`MOVE_ORDER` (移动订单)**:
    *   **检查**: 同`CANCEL_ORDER`。
    *   **处理**: 它会先像`CANCEL_ORDER`一样处理旧订单，然后再像`PLACE_ORDER`一样处理新价格的订单，但这一切都是在“预处理”层面，真实的资金变化要等撮合结果。

4.  **`ADJUST_BALANCE` (调整余额)**:
    *   **检查**: 用户是否存在。
    *   **处理**: 直接修改用户的 `balance`。这是一个纯管理操作，不会进入撮合引擎。

#### 关于“按数量市价买入”的特别说明

`RiskEngine` 的设计严格区分了“按金额”和“按数量”的市价单。为了深入理解“按数量市价买入”这一复杂功能的挑战、设计权衡及最终确定的最佳实践方案，请参阅专门的深度解析文档：

*   **[深度解析：市价单按数量买入的设计与实现](./DEEP_DIVE_MARKET_BUY_BY_QUANTITY.cn.md)**

### 核心数据结构

`RiskEngine` 的所有逻辑都围绕着两个核心的、存储在内存中的 `Map` (具体实现是 `LongObjectHashMap`，为了性能) 来展开：

1.  **`users` (`LongObjectHashMap<UserProfile>`)**:
    *   **Key**: `uid` (用户ID)
    *   **Value**: `UserProfile` 对象，包含了该用户的所有信息。

2.  **`accounts` (`IntObjectHashMap<UserCurrencyAccount>`)**:
    *   这是 `UserProfile` 内部的一个 `Map`。
    *   **Key**: `currency` (币种代码)
    *   **Value**: `UserCurrencyAccount` 对象，存储了用户在**某个特定币种**下的所有资金信息，包括：
        *   `balance`: 总余额
        *   `heldAmount`: 因挂单而被冻结的金额
        *   **可用余额**的计算公式就是 `balance - heldAmount`。

### 风险验证失败（“短路”机制）

当 `RiskEngine` 验证失败时，会发生一个**“短路”（Short-circuit）**操作，这是交易系统非常重要的一个性能和安全特性。

具体流程如下：

1.  **设置拒绝码**: 在 `preProcessCommand` 方法中，如果一个 `PLACE_ORDER` 命令因为任何原因验证失败（最常见的是余额不足，即 NSF - Not Sufficient Funds），`RiskEngine` 会立即将该 `OrderCommand` 对象的 `resultCode` 设置为一个明确的失败代码，例如 `RISK_NSF`。

2.  **跳过撮合**: `MatchingEngineRouter`（撮合引擎）收到这个命令后，会检查其 `resultCode`。当它发现命令已被标记为失败时，它会**完全跳过**对这个命令的所有撮合逻辑，直接“原样”将其传递给下一个阶段。

3.  **保证流程完整性**: 即使命令被拒绝，它**仍然会走完整个流水线**，而不会从中途消失。这保证了 Disruptor 的 `Sequence` 机制不会被打乱，所有处理器都能正确地协同工作。

4.  **明确的失败反馈**: 最终，`ResultsHandler` 会将这个带有失败代码的命令传递出去，客户端会收到一个精确的失败原因（例如“余额不足”），而不是一个模糊的“处理失败”。

这个“短路”机制是高性能系统中一个非常优雅的设计，它既能快速拒绝无效操作，又不会破坏整个处理流水线的完整性和一致性。

### 总结：`RiskEngine` 的本质

`RiskEngine` 的本质是一个**基于内存的、高性能的账户和头寸状态机**。

*   它通过**持有锁（`heldAmount`）**机制，确保了在订单进入撮合引擎这个“黑盒”之前，用户的资金已经被安全地预留出来。
*   它通过**两阶段处理（`preProcessCommand` 和 `handlerRiskRelease`）**，保证了无论撮合结果如何（完全成交、部分成交、未成交），用户的最终账户状态都能和撮合结果保持绝对一致。

这种设计使得 `RiskEngine` 成为整个交易系统的**资金安全基石**。

---

## MatchingEngineRouter 组件深度解析

`MatchingEngineRouter` 是 Disruptor 流水线中的**第三道关卡**，也是整个交易系统的“心脏”，负责执行最核心的**订单撮合**功能。

如果说 `RiskEngine` 是“管钱的”，那么 `MatchingEngineRouter` 就是“管交易的”。它的职责听起来很简单，但实现起来非常精妙。

### 核心职责：路由与撮合

`MatchingEngineRouter` 的名字里包含了它的两个核心职责：

1.  **Router (路由器)**:
    *   系统里可能同时存在成百上千个交易对（BTC/USDT, ETH/USDT, ...）。每一个交易对都有一个自己独立的**订单簿 (Order Book)**。
    *   `MatchingEngineRouter` 的首要任务，就是根据订单命令 (`OrderCommand`) 中的 `symbolId`（交易对ID），像一个交通警察一样，把这个订单**派发**给正确的订单簿去处理。
    *   它内部维护了一个 `Map`，`key` 是 `symbolId`，`value` 就是对应交易对的 `IOrderBook` 实例。

2.  **Matching Engine (撮合引擎)**:
    *   当订单被派发到具体的 `IOrderBook` 实例后，真正的撮合逻辑就开始了。
    *   `IOrderBook` 是一个数据结构，它维护了该交易对所有未成交的买单和卖单，并按价格优先、时间优先的原则排序。
    *   **撮合过程**:
        *   **新订单是买单？**: 就去订单簿里找价格最低的卖单。如果新订单的出价 >= 最低卖价，成交！然后继续找下一个最低卖价的卖单，直到订单完全成交，或者再也找不到价格合适的对手单。
        *   **新订单是卖单？**: 就去订单簿里找价格最高的买单。如果新订单的要价 <= 最高买价，成交！然后继续找下一个最高买价的买单。
        *   **如果找不到对手单？**: 那么这个新订单就会被留在订单簿里，成为新的挂单 (Maker Order)，等待别人来和它成交。

### `MatchingEngineRouter` 的关键交互

1.  **接收输入**:
    *   它从 `RiskEngine` 和 `GroupingProcessor` 接收已经通过了“风险检查”和“批次划分”的 `OrderCommand`。
    *   **重要**: 它必须等待**同一个** `OrderCommand` 被前两个处理器都处理完毕后才能开始工作，这是由 Disruptor 的 `SequenceBarrier` 保证的。

2.  **处理逻辑**:
    *   **检查“短路”信号**: 在做任何事情之前，它会先检查 `OrderCommand` 的 `resultCode`。如果 `RiskEngine` 已经把这个订单标记为失败（比如 `RISK_NSF`），`MatchingEngineRouter` 会**完全跳过**所有撮合逻辑，直接把这个失败的命令原样传递下去。
    *   **执行撮合**: 对于合法的订单，它会调用相应 `IOrderBook` 的 `match(OrderCommand)` 方法。
    *   **记录结果**: `IOrderBook` 在撮合过程中，会产生一系列的**交易事件 (`MatcherTradeEvent`)**。这些事件详细记录了每一笔撮合的细节（成交了谁的订单、成交价格、成交数量等）。`MatchingEngineRouter` 会把这些事件像链表一样串起来，挂在 `OrderCommand` 上。

3.  **产生输出**:
    *   处理完成后，`MatchingEngineRouter` 会将这个被“丰富”了的 `OrderCommand`（现在它包含了撮合结果）传递给流水线的下一个、也是最后一个阶段：`ResultsHandler`。

### 订单簿 (`IOrderBook`) 的实现

`exchange-core` 提供了两种 `IOrderBook` 的实现，它们在设计哲学和性能表现上有着天壤之别。为了深入理解这两种实现的内部工作原理、数据结构和性能权衡，请参阅专门的深度解析文档：

*   **[深度解析：订单簿 (Order Book) 的两种实现](./DEEP_DIVE_ORDERBOOK.cn.md)**

简单来说：
*   **`OrderBookNaiveImpl`**: 使用标准 `TreeMap`，易于理解，适合入门和测试。
*   **`OrderBookDirectImpl`**: 使用自定义的链表和基数树，并配合对象池技术，为极致性能而生，是生产环境的首选。

### 总结

`MatchingEngineRouter` 是连接**风险控制**和**交易执行**的核心枢纽。

*   它扮演着**分发者**的角色，确保每个订单都能找到自己的战场（订单簿）。
*   它驱动着**执行者** (`IOrderBook`)，完成最核心的价值交换（订单撮合）。
*   它还是一个**记录员**，把撮合的每一个细节都清晰地记录在 `MatcherTradeEvent` 中，为下游的清算和结算提供不可篡改的依据。

它的设计体现了单一职责原则：它只关心“撮合”这一件事，并把它做到极致。它不关心用户余额（这是 `RiskEngine` 的事），也不关心最终结果如何通知用户（这是 `ResultsHandler` 的事）。
