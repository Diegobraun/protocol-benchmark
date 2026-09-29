package br.com.diegobraun.protocolbench.server.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Map;
import java.util.stream.Collectors;

public final class GrpcAuthInterceptor implements ServerInterceptor {

    public static final Context.Key<String> CLIENT_NAME = Context.key("client-name");
    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    private final Map<String, String> clientsByToken;

    public GrpcAuthInterceptor(Map<String, String> tokensByClient) {
        this.clientsByToken = tokensByClient.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    public boolean enabled() {
        return !clientsByToken.isEmpty();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!enabled()) {
            return next.startCall(call, headers);
        }
        String client = clientFor(headers.get(AUTHORIZATION));
        if (client == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid bearer token"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        Context context = Context.current().withValue(CLIENT_NAME, client);
        return Contexts.interceptCall(context, call, headers, next);
    }

    private String clientFor(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return clientsByToken.get(authorization.substring(BEARER_PREFIX.length()));
    }
}
