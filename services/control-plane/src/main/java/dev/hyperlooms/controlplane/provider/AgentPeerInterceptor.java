package dev.hyperlooms.controlplane.provider;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/** Derives identity only from the client certificate verified by the TLS transport. */
public final class AgentPeerInterceptor implements ServerInterceptor {

    static final Context.Key<ProviderPeerIdentity> PEER = Context.key("provider-peer");

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
            ServerCall<RequestT, ResponseT> call,
            Metadata headers,
            ServerCallHandler<RequestT, ResponseT> next) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("mTLS client certificate required"),
                    new Metadata());
            return new ServerCall.Listener<>() { };
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                throw new ProviderIdentityDeniedException();
            }
            ProviderPeerIdentity peer = ProviderPeerIdentity.fromCertificate(leaf);
            return Contexts.interceptCall(Context.current().withValue(PEER, peer),
                    call, headers, next);
        } catch (SSLPeerUnverifiedException | ProviderIdentityDeniedException exception) {
            call.close(Status.UNAUTHENTICATED.withDescription("invalid provider certificate"),
                    new Metadata());
            return new ServerCall.Listener<>() { };
        }
    }
}
