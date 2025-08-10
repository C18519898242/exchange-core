package exchange.core2.gateway;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public class AuthInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        final String fullMethodName = call.getMethodDescriptor().getFullMethodName();

        // Bypass login method, as it does not require authentication
        if (fullMethodName.endsWith("Login")) {
            return next.startCall(call, headers);
        }

        // For all other methods, require a valid token
        String token = headers.get(AuthService.AUTH_TOKEN_METADATA_KEY);
        if (token == null) {
            log.warn("Authentication token is missing for call to {}", fullMethodName);
            call.close(Status.UNAUTHENTICATED.withDescription("Auth token is missing."), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        String username = AuthService.getInstance().getUsernameForToken(token);
        if (username == null) {
            log.warn("Invalid authentication token for call to {}", fullMethodName);
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid auth token."), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        // If token is valid, put username in context and proceed
        Context context = Context.current().withValue(AuthService.USERNAME_CONTEXT_KEY, username);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
