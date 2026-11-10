package academy.vrtraining.platform.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RustFsRangeDownloaderTest {
    @TempDir Path temp;

    @Test void resumesInterruptedRustFsGetWithRangeAndIfRange() throws Exception {
        byte[] body = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bucket/world.pak", exchange -> {
            int call = calls.incrementAndGet();
            byte[] response;
            if (call == 1) {
                response = java.util.Arrays.copyOfRange(body, 0, 8);
                exchange.getResponseHeaders().add("ETag", "\"stable-etag\"");
                exchange.sendResponseHeaders(200, response.length);
            } else {
                if (!"bytes=8-".equals(exchange.getRequestHeaders().getFirst("Range"))
                        || !"\"stable-etag\"".equals(exchange.getRequestHeaders().getFirst("If-Range"))) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                response = java.util.Arrays.copyOfRange(body, 8, body.length);
                exchange.getResponseHeaders().add("ETag", "\"stable-etag\"");
                exchange.getResponseHeaders().add("Content-Range", "bytes 8-15/16");
                exchange.sendResponseHeaders(206, response.length);
            }
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() +
                    "/bucket/world.pak?X-Amz-Signature=secret");
            var downloader = new RustFsRangeDownloader(url, true);
            Path part = temp.resolve("world.part");
            assertThrows(java.io.IOException.class,
                    () -> downloader.download(url, part, body.length));
            assertEquals(8, Files.size(part));
            assertArrayEquals(body, Files.readAllBytes(downloader.download(url, part, body.length)));
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsDifferentOriginAndUnexpectedRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.invalid/download");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            var downloader = new RustFsRangeDownloader(url, true);
            assertThrows(IllegalArgumentException.class,
                    () -> downloader.download(URI.create("https://evil.invalid/file"),
                            temp.resolve("other.part"), 5));
            assertThrows(java.io.IOException.class,
                    () -> downloader.download(url, temp.resolve("redirect.part"), 5));
        } finally { server.stop(0); }
    }

    @Test void productionRefusesPlainHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> new RustFsRangeDownloader(URI.create("http://127.0.0.1:9000"), false));
    }
}
