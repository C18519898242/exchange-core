# TODO List

This document is used to track and manage future development tasks and feature planning for the `exchange-core` project.

## Pending Tasks

- [ ] **Feature Implementation: Market Buy by Quantity**
  - **Description**: Implement the `placeMarketBuyOrderByQuantity()` method in `ExchangeApi` to safely convert "by quantity" market buy requests into "by amount" requests.
  - **Key Points**:
    - A price query function needs to be injected into `ExchangeApi`.
    - A risk multiplier configuration needs to be added to `CoreSymbolSpecification`.
    - Implement the "best-effort" fund calculation logic.
  - **Related Document**: [Deep Dive: Designing and Implementing Market Buy by Quantity](./DEEP_DIVE_MARKET_BUY_BY_QUANTITY.md)

- [ ] **Architecture & Implementation: Gateway Layer**
  - **Description**: Design and implement one or more gateway layers responsible for handling external client connections and protocol translation, such as receiving requests via TCP/JSON or Websockets.
  - **Key Points**:
    - The gateway is responsible for network communication, protocol parsing, user authentication, and traffic control.
    - The gateway should call the interfaces provided by `ExchangeApi` to execute trading operations.
    - Keep the gateway layer "thin," with core trading logic remaining inside `exchange-core`.
