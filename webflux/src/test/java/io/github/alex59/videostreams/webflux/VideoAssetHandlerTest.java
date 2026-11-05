package io.github.alex59.videostreams.webflux;

import io.github.alex59.filestreams.*;
import io.github.alex59.videostreams.VideoStreams;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class VideoAssetHandlerTest {
    @Test void servesOnlyMappedGetAndKeepsExistingFileResponseContract() {
        AtomicReference<String> requested = new AtomicReference<>();
        ObjectStreams storage = new ObjectStreams() {
            public Mono<UploadResult> upload(UploadRequest request, Publisher<ByteBuffer> body) {
                return Mono.error(new AssertionError("GET adapter must never upload"));
            }
            public Mono<ObjectDownload> download(String key, String range) {
                requested.set(key);
                return Mono.just(new ObjectDownload(3, "video/mp4", "opaque", null,
                        Flux.just(ByteBuffer.wrap(new byte[]{1, 2, 3})), () -> {}));
            }
        };
        var handler = new VideoAssetHandler(storage, new StreamLimits(1024, 1024));
        UUID generation = UUID.randomUUID();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/host-chosen-route"));
        handler.handle(exchange, "alice:clip", generation, "init.mp4").block();
        assertEquals(200, exchange.getResponse().getStatusCode().value());
        assertEquals(3, exchange.getResponse().getHeaders().getContentLength());
        assertEquals(VideoStreams.assetKey("alice:clip", generation, "init.mp4"), requested.get());
        requested.set(null);
        var badName = MockServerWebExchange.from(MockServerHttpRequest.get("/host-chosen-route"));
        handler.handle(badName, "alice:clip", generation, "../file").block();
        assertEquals(400, badName.getResponse().getStatusCode().value());
        assertNull(requested.get());
        var write = MockServerWebExchange.from(MockServerHttpRequest.put("/host-chosen-route"));
        handler.handle(write, "alice:clip", generation, "init.mp4").block();
        assertEquals(405, write.getResponse().getStatusCode().value());
        assertNull(requested.get());
    }
}
