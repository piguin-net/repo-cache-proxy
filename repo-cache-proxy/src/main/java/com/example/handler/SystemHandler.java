package com.example.handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.CacheManager;
import com.example.CacheManager.Attribute;
import com.example.Setting;
import com.example.Util;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class SystemHandler {
    private static Logger logger = LoggerFactory.getLogger(SystemHandler.class);

    private final Setting setting;
    private final CacheManager database;
    private final ObjectMapper mapper = new ObjectMapper();

    public SystemHandler(Setting setting, CacheManager database) {
        this.setting = setting;
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

    public HttpHandler meta() {
        return (exchange) -> {
            Map<String, Attribute> meta = new TreeMap<>(this.database.get());
            byte[] response = this.mapper.writeValueAsString(meta).getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseHeaders().add("content-type", "application/json");
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        };
    }

    public HttpHandler bulk() {
        return (exchange) -> {
            try {
                switch (exchange.getRequestMethod().toUpperCase()) {
                    case "POST":
                        this.download(exchange);
                        break;
                    case "DELETE":
                        this.delete(exchange);
                        break;
                    default:
                        exchange.sendResponseHeaders(404, 0);
                        exchange.getResponseBody().close();
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        };
    }

    public void download(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes());
        List<String> paths = this.mapper.readValue(body, new TypeReference<ArrayList<String>>(){});
        PipedInputStream pipe = new PipedInputStream();
        new Thread(() -> {
            try (ArchiveOutputStream<TarArchiveEntry> tar = new TarArchiveOutputStream(new PipedOutputStream(pipe))) {
                for (String path: paths) {
                    Attribute attr = this.database.get(path);
                    if (attr != null) {
                        File cache = Util.getCacheFilePath(this.setting.getDatabase().toFile(), attr.digest);
                        TarArchiveEntry entry = tar.createArchiveEntry(cache, path);
                        tar.putArchiveEntry(entry);
                        try (InputStream input = new FileInputStream(cache)) {
                            input.transferTo(tar);
                        }
                        tar.closeArchiveEntry();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
        exchange.sendResponseHeaders(200, /*HttpExchange.RSPBODY_CHUNKED*/0);
        exchange.getResponseHeaders().add("Content-Type", "application/x-tar");
        exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"bulk.tar\"");
        try (OutputStream out = exchange.getResponseBody()) {
            pipe.transferTo(out);
        }
    }

    public void delete(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes());
        List<String> paths = this.mapper.readValue(body, new TypeReference<ArrayList<String>>(){});
        for (String path: paths) {
            this.database.delete(path);
        }
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }
}
