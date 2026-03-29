package com.example.moodmusic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpHelloServerTest {

    private HttpHelloServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new HttpHelloServer();
        server.start(0); // bind to a random free port
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void helloEndpointReturnsHelloWorldJson() throws Exception {
        String url = "http://127.0.0.1:" + server.getPort() + "/hello";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        assertEquals(200, status);
        String contentType = conn.getHeaderField("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.toLowerCase().contains("application/json"));

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String body = br.readLine();
            assertEquals("{\"message\":\"Hello World\"}", body);
        }
    }
}
