# 代码逻辑说明

本文档旨在说明 `exchange-core` 服务启动模块的核心设计和代码逻辑。

## 核心组件

项目启动和核心实例管理主要由以下三个类负责，它们各自职责分明：

1.  `exchange.core2.service.AppConfig`: **全局配置中心**。此类采用单例模式，在程序启动时加载 `application.yml` 文件，并为整个应用程序提供统一、只读的配置访问接口。
2.  `exchange.core2.service.ExchangeService`: **核心服务管理器**。此类是 `ExchangeCore` 实例的工厂和生命周期管理器。它从 `AppConfig` 获取所需配置，并负责创建、初始化和关闭核心引擎。
3.  `exchange.core2.service.ExchangeCoreStarter`: **应用程序启动器**。此类是程序的唯一入口点 (`main` 方法所在处)，其职责被精简到最少，仅负责触发配置加载和启动服务。

## 设计思想

我们采用了**分层设计**，将**配置**、**服务**和**启动**三个关注点完全分离：

*   **`AppConfig` (配置层)**:
    *   **单例模式**: 确保全局只有一个配置实例，通过 `AppConfig.getInstance()` 在任何地方安全访问。
    *   **启动时加载**: 在类加载时（`static` 块）或首次访问时，自动从 `application.yml` 读取所有配置，避免了代码中散落的配置读取逻辑。
    *   **高内聚**: 所有与配置相关的逻辑（文件路径、解析、存储）都内聚在此类中。

*   **`ExchangeService` (服务层)**:
    *   **依赖配置**: 它不直接读取配置文件，而是依赖 `AppConfig` 提供的数据。这使得 `ExchangeService` 与配置文件的具体格式（YAML, Properties等）解耦。
    *   **封装核心逻辑**: 负责根据 `AppConfig` 提供的 `performance.profile` 动态构建 `PerformanceConfiguration`，这是其核心业务逻辑之一。
    *   **无状态方法**: 其 `coldStart()` 和 `hotStart()` 方法是无参的，因为所有需要的信息都已通过 `AppConfig` 获得，这使得API非常简洁。

*   **`ExchangeCoreStarter` (启动层)**:
    *   **职责极简**: `main` 方法只做两件事：1. 初始化 `AppConfig`。 2. 根据用户输入调用 `ExchangeService`。
    *   **清晰的入口**: 作为程序的起点，其代码非常直观，不包含任何复杂的业务或配置逻辑。

## 启动流程

1.  JVM 执行 `ExchangeCoreStarter` 的 `main` 方法。
2.  `main` 方法调用 `AppConfig.getInstance()`。这是第一次调用，会触发 `AppConfig` 单例的创建，并加载 `application.yml` 到内存中。
3.  程序向用户询问启动模式（冷启动或热启动）。
4.  根据用户的选择，调用无参的 `ExchangeService.coldStart()` 或 `ExchangeService.hotStart(...)`。
5.  `ExchangeService` 内部调用 `AppConfig.getInstance()` 获取配置，并根据配置构建 `PerformanceConfiguration` 和 `ExchangeConfiguration`。
6.  `ExchangeService` 创建并返回 `ExchangeCore` 的单例实例。
7.  `main` 方法获取到创建好的 `exchangeCore` 实例，并调用其 `startup()` 方法。
8.  Disruptor 和其他核心组件开始运行。
9.  `main` 方法注册一个 `ShutdownHook`，确保在程序退出时能调用 `ExchangeService.shutdown()` 来优雅地关闭核心引擎。
