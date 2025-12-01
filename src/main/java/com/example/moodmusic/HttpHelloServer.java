package com.example.moodmusic;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal embedded HTTP server that exposes a single endpoint:
 *   GET /hello -> { "message": "Hello World" }
 */
public class HttpHelloServer {
    private HttpServer server;
    private ExecutorService executor;

    /**
     * Starts the server on the given port. If port is 0, the system will pick a free port.
     */
    public synchronized void start(int port) throws IOException {
        if (server != null) return; // already started
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/hello", new HelloHandler());
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Returns the actual bound port. Useful when starting with port 0.
     */
    public synchronized int getPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /**
     * Stops the server.
     */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static class HelloHandler implements HttpHandler {
        private static final byte[] BODY = "{\"message\":\"Hello World\"}".getBytes(StandardCharsets.UTF_8);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!Objects.equals(exchange.getRequestMethod(), "GET")) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (!"/hello".equals(exchange.getRequestURI().getPath())) {
                    send(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(exchange, 200, "application/json; charset=utf-8", BODY);
            } finally {
                exchange.close();
            }
        }

        private void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
            Headers headers = ex.getResponseHeaders();
            headers.set("Content-Type", contentType);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
