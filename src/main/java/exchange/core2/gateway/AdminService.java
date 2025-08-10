package exchange.core2.gateway;

import exchange.core2.core.ExchangeApi;
import exchange.core2.gateway.proto.*;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class AdminService extends AdminServiceGrpc.AdminServiceImplBase {

    private final ExchangeApi exchangeApi;
    private final AuthService authService;

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

        final Context.CancellableContext context = Context.current().withCancellation();
        final boolean success = authService.login(username, password, context);

        if (!success) {
            responseObserver.onNext(LoginResponse.newBuilder().setSuccess(false).setMessage("Invalid credentials").build());
            responseObserver.onCompleted();
            return;
        }

        final Context newContext = Context.current().withValue(AuthService.USERNAME_CONTEXT_KEY, username);
        newContext.run(() -> {
            responseObserver.onNext(LoginResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        });
    }
}
