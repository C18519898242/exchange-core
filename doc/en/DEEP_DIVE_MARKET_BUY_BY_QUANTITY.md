# Deep Dive: Designing and Implementing Market Buy by Quantity

This document provides a detailed record of the in-depth discussion on the "Market Buy by Quantity" feature, covering its inherent challenges, a trade-off analysis of various implementation solutions, and the final determined best-practice architecture.

## 1. The Core Problem: Why is "Market Buy by Quantity" Complicated?

Unlike "Market Buy by Amount" (e.g., buying ETH with 10,000 USDT), "Market Buy by Quantity" (e.g., buying 2 ETH) introduces significant uncertainty for the `RiskEngine`.

A core responsibility of the `RiskEngine` is to **hold a deterministic amount of funds** before an order enters the matching engine to ensure the trade can be completed. However, for a "by quantity" market buy order, the future execution price is unknown, making it impossible to know how much quote currency (like USDT) to hold in advance.

*   **Holding Too Little**: If the market price spikes at the moment of execution, the pre-held funds might be insufficient to buy the specified quantity, leading to order failure, partial fills, or even negative balances.
*   **Holding Too Much**: Holding an excessive amount of the user's funds severely impacts their capital efficiency and leads to a poor user experience.

## 2. Exploring Solutions: Where Should the Conversion Logic Live?

To solve this, the core idea is to **convert** the "by quantity" request into a safe "by amount" request that the core engine can understand. The key question is at which layer of the system this conversion logic should be implemented.

We explored three main approaches:

### Approach A: Implement in the Core Risk Logic (`RiskEngine`) (Rejected)

*   **Process**: The `RiskEngine` receives a "by quantity" order, queries the order book for the current market price, multiplies it by a risk factor to calculate the required hold amount, and then modifies the order to a "by amount" type.
*   **Pros**: Completely transparent to the client.
*   **Cons (Fatal)**:
    *   **Breaks Architecture**: Severely violates the unidirectional data flow principle of the Disruptor pipeline. The `RiskEngine` (Stage 1) would have a reverse dependency on data (price) from the `MatchingEngine` (Stage 2), leading to component coupling and performance bottlenecks.
    *   **Introduces Race Conditions**: Under high concurrency, multiple orders querying the price and holding funds simultaneously could lead to incorrect fund calculations due to state update delays.

### Approach B: Implement in a Separate "Gateway" (Acceptable, but Not Optimal)

*   **Process**: A gateway service separate from `exchange-core` (e.g., a service handling TCP/JSON requests) is responsible for the conversion logic. It subscribes to market data, and upon receiving a "by quantity" request, it performs the calculation and conversion before calling the standard `ExchangeApi` interface.
*   **Pros**:
    *   **Pure Core**: The `exchange-core` library remains minimal, only performing the most essential tasks.
    *   **Flexible Strategy**: Different gateways can implement different conversion strategies.
*   **Cons**:
    *   **Code Duplication**: If there are multiple gateways, the logic must be implemented repeatedly, making it difficult to maintain.
    *   **Slightly Worse Performance**: The gateway acquires market data over the network, which has higher latency than in-process access.

### Approach C: Implement in `ExchangeApi` (Final Adopted Solution)

*   **Process**: `ExchangeApi`, serving as the unified entry point (internal gateway) for the `exchange-core` library, is extended with a high-level method specifically for this purpose, such as `placeMarketBuyOrderByQuantity()`.
*   **Implementation Details**:
    1.  **Safe Price Retrieval**: During the `ExchangeCore` construction, a lightweight "price query function" that can safely access the best ask price is **injected** into the `ExchangeApi`. This avoids breaking pipeline rules.
    2.  **Flexible Risk Configuration**: The risk multiplier (e.g., 1.2x) is made a part of the `CoreSymbolSpecification`, making it configurable per trading pair instead of being hardcoded.
    3.  **"Best-Effort" Strategy**: In the new `ExchangeApi` method, the ideal hold amount (`quantity * price * multiplier`) is calculated and compared with the user's actual available balance. The **lesser** of the two is used as the final order amount. This significantly improves user experience by preventing order failures due to slightly insufficient funds.
    4.  **Call Standard Interface**: After the conversion, the method calls the internal, standard `submitCommandAsync` to place a fully-funded "by amount" market order onto the `RingBuffer`.

## 3. Core Trade-offs and Conclusions

### On the "Small Probability of Non-Fill" Issue

We confirmed that due to the parallel nature of the pipeline, the system employs a conservative fund-checking strategy. When checking a new order, it only considers the currently available balance and does not "wait" for funds that might be released from previous orders.

This can lead to a scenario where a user's 8,000 USDT order only fills for 6,000, theoretically releasing 2,000, but a subsequent 3,000 USDT order is still rejected because the available balance wasn't updated at the moment of the check.

*   **Conclusion**: This behavior is a deliberate trade-off made to ensure **absolute safety** and **logical simplicity** in a high-performance system. For manual traders, it's virtually impossible to encounter this due to human reaction times. For high-frequency traders, this is expected behavior that they must handle in their client-side strategies.

### Final Architectural Decision

**Implementing the "market buy by quantity" conversion logic within `ExchangeApi` is the optimal solution that balances usability, safety, performance, and architectural clarity.**

*   **For Users**: The `exchange-core` library directly offers powerful, user-friendly functionality.
*   **For the Core Engine**: Core components like `RiskEngine` remain pure, and their high performance and stability are unaffected.
*   **For the Architecture**: Responsibilities are clear. The core trading logic is cohesive within the `exchange-core` library, preventing logic duplication across multiple external components and improving system maintainability and consistency.
