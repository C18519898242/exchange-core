package exchange.core2.gateway;

import com.google.protobuf.Empty;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.gateway.proto.*;
import exchange.core2.service.ExchangeService;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

@Slf4j
public final class AdminService extends AdminServiceGrpc.AdminServiceImplBase {

    private final ExchangeApi exchangeApi = ExchangeService.getApi();
    private final AuthService authService = AuthService.getInstance();
    private final SingleChronicleQueue adminQueue = AdminQueueService.getInstance().getQueue();

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        log.info("Ping received");
        final PingResponse response = PingResponse.newBuilder()
                .setMessage("pong")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        final String username = request.getUsername();
        final String password = request.getPassword();

        final String token = AuthService.getInstance().login(username, password);

        LoginResponse.Builder responseBuilder = LoginResponse.newBuilder();

        if (token != null) {
            responseBuilder.setSuccess(true).setToken(token);
        } else {
            responseBuilder.setSuccess(false).setMessage("Invalid credentials");
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void stopEngine(StopEngineRequest request, StreamObserver<StopEngineResponse> responseObserver) {
        log.info("Received request to stop the engine.");
        ExchangeService.shutdown();
        responseObserver.onNext(StopEngineResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void addUser(AddUserRequest request, StreamObserver<Empty> responseObserver) {
        final ApiAddUser addUserCmd = new ApiAddUser(request.getUid());

        // Submit command, but don't care about the CompletableFuture result
        exchangeApi.submitCommandAsync(addUserCmd);

        // Immediately return, indicating the request has been accepted
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void subscribeAdminEvents(SubscribeAdminEventsRequest request, StreamObserver<AdminEvent> responseObserver) {

        final ExcerptTailer tailer = adminQueue.createTailer();

        // If the client provides lastEventIndex, move to that position
        if (request.getLastEventIndex() > 0) {
            tailer.moveToIndex(request.getLastEventIndex());
        }

        // Push events in a separate thread to avoid blocking the gRPC thread
        new Thread(() -> {
            try {
                while (true) { // TODO: need a way to detect if the connection is closed
                    try (DocumentContext dc = tailer.readingDocument()) {
                        if (dc.isPresent()) {
                            Bytes<?> bytes = dc.wire().bytes();
                            AdminEvent event = AdminEvent.parseFrom(bytes.toByteArray())
                                    .toBuilder()
                                    .setIndex(dc.index()) // Attach the queue index to the event
                                    .build();
                            
                            log.debug("Sending event to client: {}", event);
                            responseObserver.onNext(event);
                        } else {
                            // No new messages in the queue, sleep briefly
                            Thread.sleep(50);
                        }
                    }
                }
            } catch (Exception e) {
                responseObserver.onError(e);
            } finally {
                responseObserver.onCompleted();
            }
        }).start();
    }
}
