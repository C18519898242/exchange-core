package exchange.core2.gateway;

import exchange.core2.core.ExchangeApi;
import exchange.core2.gateway.proto.*;
import exchange.core2.service.ExchangeService;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AdminService extends AdminServiceGrpc.AdminServiceImplBase {

    private final ExchangeApi exchangeApi = ExchangeService.getApi();
    private final AuthService authService = AuthService.getInstance();

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
}
