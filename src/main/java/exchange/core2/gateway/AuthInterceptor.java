package exchange.core2.gateway;

import io.grpc.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class AuthInterceptor implements ServerInterceptor {

    private final AuthService authService;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        final String fullMethodName = call.getMethodDescriptor().getFullMethodName();

        // Bypass login method
        if (fullMethodName.endsWith("Login")) {
            return next.startCall(call, headers);
        }

        String username = AuthService.USERNAME_CONTEXT_KEY.get();
        if (username == null) {
            log.warn("Unauthenticated access attempt to {}", fullMethodName);
            call.close(Status.UNAUTHENTICATED.withDescription("Authentication required."), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        return next.startCall(call, headers);
    }
}
