# 重构 AdminGateway 以集成 Chronicle Queue

本文档描述了将 `AdminGateway` 从简单的请求-响应模式重构为基于 `Chronicle Queue` 的可靠事件流服务的完整方案。此方案旨在使网关实现与《网关架构设计》文档中的蓝图保持一致。

## 1. 方案目标

- **可靠性**: 确保所有管理命令的结果都能被持久化，即使在网关重启或客户端断线的情况下也不会丢失。
- **顺序性**: 保证客户端接收到的管理事件严格按照它们在核心引擎中处理完成的顺序。
- **可扩展性**: 为未来添加更多类型的管理事件（如余额更新）提供一个统一的事件流框架。
- **架构一致性**: 使 `AdminGateway` 的实现与架构设计文档完全对齐。

## 2. 实施步骤

### 第一步：更新项目依赖 (`pom.xml`)

在 `pom.xml` 文件中，添加 `chronicle-queue` 的依赖。

```xml
&lt;dependency&gt;
    &lt;groupId&gt;net.openhft&lt;/groupId&gt;
    &lt;artifactId&gt;chronicle-queue&lt;/artifactId&gt;
    &lt;version&gt;5.22.2&lt;/version&gt; &lt;!-- 建议使用一个较新的稳定版本 --&gt;
&lt;/dependency&gt;
```

### 第二步：修改 API 契约 (`admin.proto`)

重构 gRPC 接口，从一元响应模式切换到服务端流模式。

```protobuf
syntax = "proto3";

package exchange.core2.gateway.proto;

// 导入 Empty 类型
import "google/protobuf/empty.proto";

option java_multiple_files = true;
option java_package = "exchange.core2.gateway.proto";
option java_outer_classname = "AdminProto";

service AdminService {
  // Ping, Login, StopEngine 保持不变
  rpc Ping(PingRequest) returns (PingResponse) {}
  rpc Login(LoginRequest) returns (LoginResponse) {}
  rpc StopEngine(StopEngineRequest) returns (StopEngineResponse) {}

  // AddUser 现在变为异步提交，立即返回 Empty 表示请求已接受
  rpc addUser(AddUserRequest) returns (google.protobuf.Empty) {}

  // 新增：订阅管理事件流的方法
  rpc subscribeAdminEvents(SubscribeAdminEventsRequest) returns (stream AdminEvent);
}

// --- 消息定义 ---

message AddUserRequest {
  int64 uid = 1;
}

// 订阅请求，包含 lastEventIndex 用于断线续传
message SubscribeAdminEventsRequest {
  int64 lastEventIndex = 1; // 首次订阅时客户端应发送 0
}

// 统一的管理事件包装器
message AdminEvent {
  // 事件在 Chronicle Queue 中的唯一索引，用于客户端追踪
  int64 index = 1;

  // 使用 oneof 来支持未来扩展更多事件类型
  oneof event_type {
    CommandResult command_result = 2;
    // BalanceUpdateEvent balance_update = 3; // 示例
  }
}

// 命令结果消息
message CommandResult {
  // 可以添加一个关联 ID 来匹配请求和响应
  string correlationId = 1;
  int64 uid = 2;
  ResultCode resultCode = 3;
  string message = 4;
}

enum ResultCode {
  SUCCESS = 0;
  USER_ALREADY_EXISTS = 1;
  // ... 其他结果码
}

// Ping, Login, StopEngine 的消息定义保持不变...
message PingRequest {}
message PingResponse { string message = 1; }
message LoginRequest { string username = 1; string password = 2; }
message LoginResponse { bool success = 1; string message = 2; string token = 3; }
message StopEngineRequest {}
message StopEngineResponse { bool success = 1; }
```

### 第三步：创建事件发布服务 (`EventPublishService`)

创建一个新的服务，该服务实现了 `IEventsHandler` 接口，负责将从核心引擎传来的事件写入 `Chronicle Queue`。

- **文件路径**: `src/main/java/exchange/core2/gateway/EventPublishService.java`

```java
package exchange.core2.gateway;

import exchange.core2.core.IEventsHandler;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.gateway.proto.AdminEvent;
import exchange.core2.gateway.proto.CommandResult;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

public class EventPublishService implements IEventsHandler {

    private final SingleChronicleQueue adminQueue;
    private final ExcerptAppender appender;

    public EventPublishService(String queuePath) {
        this.adminQueue = SingleChronicleQueueBuilder.binary(queuePath).build();
        this.appender = adminQueue.acquireAppender();
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
        // 过滤出需要通过 AdminGateway 推送的命令结果
        if (isAdministrativeCommand(commandResult.getCommand())) {
            
            // 将内部事件转换为 Protobuf 事件
            CommandResult protoResult = CommandResult.newBuilder()
                    .setUid(commandResult.getCommand().uid)
                    // TODO: 实现一个转换函数
                    // .setResultCode(toGrpcResultCode(commandResult.getResultCode())) 
                    .setMessage(commandResult.getResultCode().toString())
                    .build();

            AdminEvent protoEvent = AdminEvent.newBuilder()
                    .setCommandResult(protoResult)
                    .build();

            // 将 Protobuf 消息序列化后写入队列
            appender.writeBytes(b -> b.write(protoEvent.toByteArray()));
        }
    }

    private boolean isAdministrativeCommand(ApiCommand cmd) {
        // 在这里定义哪些命令属于管理类命令
        switch (cmd.getCommandType()) {
            case ADD_USER:
            case ADJUST_BALANCE:
            case SUSPEND_USER:
            case RESUME_USER:
                return true;
            default:
                return false;
        }
    }

    // IEventsHandler 的其他方法可以根据需要实现，或暂时留空
    @Override public void tradeEvent(TradeEvent tradeEvent) {}
    @Override public void reduceEvent(ReduceEvent reduceEvent) {}
    @Override public void rejectEvent(RejectEvent rejectEvent) {}
    @Override public void commandSuccess(OrderCommand cmd) {}
    @Override public void orderBook(OrderBook orderBook) {}
}
```

### 第四步：调整 `ExchangeCore` 初始化

修改 `ExchangeCore` 的配置，将 `EventPublishService` 作为 `SimpleEventsProcessor` 的一个下游消费者。这通常在 `ExchangeService` 或 `ExchangeCoreStarter` 中完成。

```java
// 伪代码 - 在 ExchangeService.java 或类似地方

// 1. 创建 EventPublishService 实例
String adminQueuePath = AppConfig.getInstance().getAdminQueuePath(); // 假设路径在配置中
IEventsHandler adminEventPublisher = new EventPublishService(adminQueuePath);

// 2. 获取原有的 ExchangeApi 事件处理器
IEventsHandler apiEventsHandler = exchangeApi.getApiEventsHandler();

// 3. 创建一个组合处理器，将事件同时发给两者
IEventsHandler compositeHandler = new CompositeEventsHandler(apiEventsHandler, adminEventPublisher);

// 4. 将组合处理器注入 SimpleEventsProcessor
// (这可能需要修改 SimpleEventsProcessor 的构造函数或提供一个 setter)
// ... 在构建 ExchangeCore 时 ...
SimpleEventsProcessor simpleEventsProcessor = new SimpleEventsProcessor(compositeHandler);
// ...
```
*注：可能需要创建一个 `CompositeEventsHandler` 类来实现将事件分发给多个下游。*

### 第五步：重构 `AdminService.java`

实现新的 gRPC 接口，从 `Chronicle Queue` 读取事件并以流的形式推送给客户端。

```java
package exchange.core2.gateway;

import com.google.protobuf.Empty;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.gateway.proto.*;
import exchange.core2.service.ExchangeService;
import io.grpc.stub.StreamObserver;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

public final class AdminService extends AdminServiceGrpc.AdminServiceImplBase {

    private final ExchangeApi exchangeApi = ExchangeService.getApi();
    private final SingleChronicleQueue adminQueue;

    public AdminService(String queuePath) {
        this.adminQueue = SingleChronicleQueueBuilder.binary(queuePath).build();
    }

    @Override
    public void addUser(AddUserRequest request, StreamObserver<Empty> responseObserver) {
        final ApiAddUser addUserCmd = new ApiAddUser(request.getUid());
        exchangeApi.submitCommandAsync(addUserCmd); // 发后即忘
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void subscribeAdminEvents(SubscribeAdminEventsRequest request, StreamObserver<AdminEvent> responseObserver) {
        
        final ExcerptTailer tailer = adminQueue.createTailer();

        if (request.getLastEventIndex() > 0) {
            tailer.moveToIndex(request.getLastEventIndex());
        }

        // 使用一个单独的线程来推送事件，避免阻塞 gRPC 工作线程
        final Thread publisherThread = new Thread(() -> {
            try {
                // TODO: 需要一个机制来检测 gRPC 连接是否已断开，以便优雅地停止线程
                while (!Thread.currentThread().isInterrupted()) {
                    try (DocumentContext dc = tailer.readingDocument()) {
                        if (dc.isPresent()) {
                            Bytes<?> bytes = dc.wire().bytes();
                            AdminEvent event = AdminEvent.parseFrom(bytes.toByteArray())
                                    .toBuilder()
                                    .setIndex(dc.index()) // 将队列索引附加到事件上
                                    .build();
                            
                            responseObserver.onNext(event);
                        } else {
                            // 队列中没有新消息，短暂休眠以避免 CPU 空转
                            Thread.sleep(50);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
            } catch (Exception e) {
                responseObserver.onError(e);
            } finally {
                responseObserver.onCompleted();
            }
        });
        
        publisherThread.setName("AdminEvent-Publisher-" + responseObserver.hashCode());
        publisherThread.start();
        
        // TODO: 需要注册一个 onCancel/onCompleted 回调来中断 publisherThread
    }

    // Ping, Login, StopEngine 的实现保持不变...
}
```

## 3. 编译与测试

完成以上代码修改后，执行 `mvn clean package` 进行编译。

**测试步骤**:
1.  启动 `ExchangeCore` 服务。
2.  使用一个 gRPC 客户端：
    a.  首先调用 `subscribeAdminEvents` 并保持连接。
    b.  然后调用 `addUser` 方法。
    c.  在 `subscribeAdminEvents` 的流中，应该能异步地接收到 `addUser` 的 `CommandResult` 事件。
3.  **断线续传测试**:
    a.  记录收到的最后一个事件的 `index`。
    b.  断开并重新调用 `subscribeAdminEvents`，传入该 `index`。
    c.  验证事件流是否从下一条消息开始，没有丢失或重复。
