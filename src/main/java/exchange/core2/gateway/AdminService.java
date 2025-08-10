package exchange.core2.gateway;

import exchange.core2.core.ExchangeApi;
import exchange.core2.gateway.proto.AdminServiceGrpc;
import exchange.core2.gateway.proto.PingRequest;
import exchange.core2.gateway.proto.PingResponse;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class AdminService extends AdminServiceGrpc.AdminServiceImplBase {

    private final ExchangeApi exchangeApi;

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        log.info("Ping received");
        final PingResponse response = PingResponse.newBuilder()
                .setMessage("pong")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
