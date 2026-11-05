package io.github.alex59.videostreams.webflux;

import io.github.alex59.filestreams.ObjectStreams;
import io.github.alex59.filestreams.StreamLimits;
import io.github.alex59.filestreams.webflux.ObjectStreamHandler;
import io.github.alex59.videostreams.VideoStreams;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Route-neutral GET adapter. The application authorizes sourceKey on every artifact request. */
public final class VideoAssetHandler {
    private final ObjectStreamHandler files;

    public VideoAssetHandler(ObjectStreams objects, StreamLimits limits) {
        files = new ObjectStreamHandler(objects, limits);
    }

    public Mono<Void> handle(ServerWebExchange exchange, String sourceKey, UUID generation, String name) {
        return Mono.defer(() -> {
            if (exchange.getRequest().getMethod() != HttpMethod.GET) {
                exchange.getResponse().setStatusCode(HttpStatus.METHOD_NOT_ALLOWED);
                exchange.getResponse().getHeaders().setAllow(Set.of(HttpMethod.GET));
                return exchange.getResponse().setComplete();
            }
            final String key;
            try { key = VideoStreams.assetKey(sourceKey, generation, name); }
            catch (IllegalArgumentException invalid) {
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return exchange.getResponse().setComplete();
            }
            return files.handle(exchange, key);
        });
    }
}
