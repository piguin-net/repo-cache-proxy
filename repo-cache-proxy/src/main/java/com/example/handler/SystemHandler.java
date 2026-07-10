package com.example.handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.CacheManager;
import com.example.CacheManager.Attribute;
import com.sun.net.httpserver.HttpHandler;

import tools.jackson.databind.ObjectMapper;

public class SystemHandler {
    private static Logger logger = LoggerFactory.getLogger(SystemHandler.class);

    private final CacheManager database;
    private final ObjectMapper mapper = new ObjectMapper();

    public SystemHandler(CacheManager database) {
        this.database = database;
    }

    public HttpHandler index() {
        return (exchange) -> {
            try (InputStream input = getClass().getResourceAsStream("/index.html")) {
                exchange.sendResponseHeaders(200, /*HttpExchange.RSPBODY_CHUNKED*/0);
                try (OutputStream output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
            }
        };
    }

    public HttpHandler metadata() {
        return (exchange) -> {
            Map<String, Attribute> meta = this.database.get();
            byte[] response = this.mapper.writeValueAsString(meta).getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseHeaders().add("content-type", "application/json");
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        };
    }
}
