package com.example.handler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.CacheManager;
import com.example.CacheManager.Attribute;
import com.example.Setting;
import com.example.Util;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class CacheHandler implements HttpHandler {
    private static Logger logger = LoggerFactory.getLogger(CacheHandler.class);
    
    private final Setting setting;
    private final CacheManager database;
    private final HttpClient http;

    public CacheHandler(Setting setting, CacheManager database, HttpClient http) throws FileNotFoundException, IOException {
        this.setting = setting;
        this.database = database;
        this.http = http;
    }

    @Override
    public void handle(HttpExchange downstream) throws IOException {
        try {
            String path = downstream.getRequestURI().toString().substring(this.setting.getPath().length());
            switch (downstream.getRequestMethod().toUpperCase()) {
                case "DELETE":
                    this.handleDeleteRequest(path, downstream);
                    break;
                case "GET":
                    this.handleGetRequest(path, downstream);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new IOException(e);
        }
    }

    private void handleDeleteRequest(String path, HttpExchange downstream) throws IOException, InterruptedException {
        this.database.delete(this.setting.getPath() + path);
        downstream.sendResponseHeaders(200, 0);
        downstream.getResponseBody().close();
    }

    private void handleGetRequest(String path, HttpExchange downstream) throws IOException, InterruptedException {
        HttpResponse<InputStream> upstream = this.handleUpStream(path, downstream);
        if (upstream.statusCode() < 400) {
            this.handleDownStream(path, upstream, downstream);
        } else {
            logger.warn("upstream error response. ({})", upstream.statusCode());
            try {
                Long length = Long.valueOf(Util.getHeader(upstream.headers().map(), "content-length"));
                downstream.sendResponseHeaders(upstream.statusCode(), length);
                try (OutputStream out = downstream.getResponseBody()) {
                    upstream.body().transferTo(out);
                }
            } catch (Exception e) {
                downstream.sendResponseHeaders(upstream.statusCode(), /*HttpExchange.RSPBODY_CHUNKED*/0);
                downstream.getResponseBody().close();
            }
        }
    }

    private HttpResponse<InputStream> handleUpStream(String path, HttpExchange downstream) throws IOException, InterruptedException {
        boolean found = this.database.containsKey(this.setting.getPath() + path);
        Attribute attr = found ? this.database.get(this.setting.getPath() + path) : null;
        Map<String,String> detectors = new HashMap<>() {{
            this.put("if-modified-since", found ? attr.lastModified : null);
            this.put("if-none-match", found ? attr.etag : null);
        }};
        Builder builder = HttpRequest.newBuilder().uri(URI.create(this.setting.getUpstream() + path)).GET();
        List<String> skipHeaders = new ArrayList<>(detectors.keySet());
        skipHeaders.addAll(Arrays.asList("host", "connection"));
        for (Entry<String, List<String>> header : downstream.getRequestHeaders().entrySet()) {
            for (String value: header.getValue()) {
                logger.debug(" ->  {}: {}", header.getKey(), value);
                if (!skipHeaders.contains(header.getKey().toLowerCase())) {
                    builder.header(header.getKey(), value);
                }
            }
        }
        if (found) {
            for (Entry<String,String> detector: detectors.entrySet()) {
                builder.header(detector.getKey(), detector.getValue());
            }
        }
        HttpRequest request = builder.build();
        for (Entry<String,List<String>> header: request.headers().map().entrySet()) {
            for (String value: header.getValue()) {
                logger.debug(" ->> {}: {}", header.getKey(), value);
            }
        }
        HttpResponse<InputStream> response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 200) {
            boolean isCacheTarget = false;
            for (String detector: Arrays.asList("last-modified", "etag")) {
                for (Entry<String,List<String>> header: response.headers().map().entrySet()) {
                    if (header.getKey().toLowerCase().equals(detector)) {
                        isCacheTarget = true;
                    }
                }
            }
            if (isCacheTarget) {
                this.database.put(this.setting.getPath() + path, response.headers().map(), response.body());
            }
        }
        return response;
    }

    private void handleDownStream(String path, HttpResponse<InputStream> upstream, HttpExchange downstream) throws IOException {
        logger.debug("  <- {} {}", upstream.version(), upstream.statusCode());
        List<String> skipHeaders = Arrays.asList("content-length"/*, "last-modified", "etag"*/);
        for (Entry<String, List<String>> header : upstream.headers().map().entrySet()) {
            for (String value: header.getValue()) {
                logger.debug("  <- {}: {}", header.getKey(), value);
                if (!skipHeaders.contains(header.getKey().toLowerCase())) {
                    downstream.getResponseHeaders().add(header.getKey(), value);
                }
            }
        }
        boolean change = true;
        Attribute attr = this.database.get(this.setting.getPath() + path);
        Map<String,String> detectors = new HashMap<>() {{
            this.put("last-modified", "if-modified-since");
            this.put("etag", "if-none-match");
        }};
        for (Entry<String,List<String>> up: upstream.headers().map().entrySet()) {
            for (Entry<String,List<String>> down: downstream.getRequestHeaders().entrySet()) {
                for (Entry<String,String> detector: detectors.entrySet()) {
                    if (up.getKey().toLowerCase().equals(detector.getKey().toLowerCase())
                     && down.getKey().toLowerCase().equals(detector.getValue().toLowerCase())
                    ) {
                        // downstream.getResponseHeaders().add(detector.getKey(), up.getValue().getLast());
                        if (up.getValue().getLast().equals(down.getValue().getLast())) {
                            change = false;
                        }
                    }
                }
            }
        }
        logger.debug(" <<- {} {}", downstream.getProtocol(), change ? 200 : 304);
        for (Entry<String,List<String>> header: downstream.getResponseHeaders().entrySet()) {
            for (String value: header.getValue()) {
                logger.debug(" <<- {}: {}", header.getKey(), value);
            }
        }
        // TODO: etag優先
        if (!change) {
            downstream.sendResponseHeaders(304, 0);
        } else {
            File cache = this.database.getFile(attr);
            downstream.sendResponseHeaders(200, attr.contentLength);
            try (
                OutputStream output = downstream.getResponseBody();
                InputStream input = new BufferedInputStream(new FileInputStream(cache));
            ) {
                input.transferTo(output);
            }
        }
    }
}
