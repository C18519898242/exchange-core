# 代码逻辑说明

本文档旨在说明 `exchange-core` 服务启动模块的核心设计和代码逻辑。

## 核心组件

项目启动和核心实例管理主要由以下两个类负责：

1.  `exchange.core2.service.ExchangeService`: 核心服务类，负责 `ExchangeCore` 实例的创建、生命周期管理和访问。
2.  `exchange.core2.service.ExchangeCoreStarter`: 应用程序的唯一入口点 (`main` 方法所在处)，负责启动整个服务。

## 设计思想

我们采用了**工厂模式**和**单例模式**相结合的设计，实现了关注点分离（Separation of Concerns）：

*   **`ExchangeService` (工厂和管理器)**:
    *   **封装性**: 此类完全封装了 `ExchangeCore` 的创建细节，包括 `ExchangeConfiguration` 的构建。外部调用者无需关心 `ExchangeCore` 是如何被创建的。
    *   **单例管理**: 它内部维护一个 `static` 的 `ExchangeCore` 实例，确保在整个应用程序中只有一个核心引擎在运行。
    *   **生命周期控制**: 提供 `coldStart()`、`hotStart()` 和 `shutdown()` 等静态方法，全面控制 `ExchangeCore` 的生命周期。
    *   **统一访问点**: 通过 `getApi()` 和 `getCore()` 方法，为应用程序的其他部分提供了访问核心API和实例的统一、安全的方式。

*   **`ExchangeCoreStarter` (启动器)**:
    *   **职责单一**: 这个类的唯一职责就是作为程序的启动入口。
    *   **简洁清晰**: 其 `main` 方法非常简洁，只负责解析用户输入的启动模式（冷启动或热启动），然后调用 `ExchangeService` 的相应方法来创建和启动核心引擎。它不包含任何复杂的业务或配置逻辑。
    *   **解耦**: `ExchangeCoreStarter` 与 `ExchangeCore` 的具体实现完全解耦，它只依赖于 `ExchangeService` 提供的接口。

## 启动流程

1.  JVM 执行 `ExchangeCoreStarter` 的 `main` 方法。
2.  程序向用户询问启动模式（冷启动或热启动）。
3.  根据用户的选择，调用 `ExchangeService.coldStart()` 或 `ExchangeService.hotStart(...)`。
4.  `ExchangeService` 内部创建并配置好 `ExchangeCore` 的单例实例。
5.  `main` 方法获取到创建好的 `exchangeCore` 实例，并调用其 `startup()` 方法。
6.  Disruptor 和其他核心组件开始运行。
7.  `main` 方法注册一个 `ShutdownHook`，确保在程序退出时能调用 `ExchangeService.shutdown()` 来优雅地关闭核心引擎，释放资源。
