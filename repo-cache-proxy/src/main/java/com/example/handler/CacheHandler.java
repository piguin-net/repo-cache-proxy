package com.example.handler;

import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Map.Entry;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.ChangeDetectionHeader;
import com.example.Setting;
import com.example.Util;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class CacheHandler implements HttpHandler {
    private static Logger logger = LoggerFactory.getLogger(CacheHandler.class);
    
    private final Setting setting;
    private final RocksDB database;
    private final HttpClient http;
    private final List<ColumnFamilyHandle> columns;

    public CacheHandler(Setting setting, RocksDB database, HttpClient http, List<ColumnFamilyHandle> columns) {
        this.setting = setting;
        this.database = database;
        this.http = http;
        this.columns = columns;
    }

    // TODO: thread safe
    @Override
    public void handle(HttpExchange downstream) throws IOException {
        try {
            String path = downstream.getRequestURI().toString().substring(this.setting.getPath().length());
            switch (downstream.getRequestMethod().toUpperCase()) {
                case "GET":
                    this.handleGetRequest(path, downstream);
                    break;
                case "DELETE":
                    this.handleDeleteRequest(path, downstream);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new IOException(e);
        }
    }

    private void handleGetRequest(String path, HttpExchange downstream) throws IOException, InterruptedException, RocksDBException {
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

    private void handleDeleteRequest(String path, HttpExchange downstream) throws IOException, InterruptedException, RocksDBException {
        for (ColumnFamilyHandle column: this.columns) {
            this.database.delete(column, (this.setting.getPath() + path).getBytes());
        }
        downstream.sendResponseHeaders(200, 0);
        downstream.getResponseBody().close();
    }

    private HttpResponse<InputStream> handleUpStream(String path, HttpExchange downstream) throws IOException, InterruptedException, RocksDBException {
        Builder request = HttpRequest.newBuilder().uri(URI.create(this.setting.getUpstream() + path)).GET();
        List<String> skipHeaders = new ArrayList<>(ChangeDetectionHeader.requests());
        skipHeaders.addAll(Arrays.asList("host", "connection"));
        for (Entry<String, List<String>> header : downstream.getRequestHeaders().entrySet()) {
            if (!skipHeaders.contains(header.getKey().toLowerCase())) {
                for (String value: header.getValue()) {
                    logger.debug(" -> {}: {}", header.getKey(), value);
                    request.header(header.getKey(), value);
                }
            }
        }
        for (ChangeDetectionHeader detector: ChangeDetectionHeader.values()) {
            byte[] value = this.database.get(detector.getHandle(this.columns), (this.setting.getPath() + path).getBytes());
            if (value != null && !downstream.getRequestHeaders().containsKey(detector.request())) {
                    logger.debug(" ->> {}: {}", detector.request(), new String(value));
                    request.header(detector.request(), new String(value));
            }
        }
        HttpResponse<InputStream> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 200) {
            boolean isCacheTarget = false;
            for (ChangeDetectionHeader detector: ChangeDetectionHeader.values()) {
                for (Entry<String,List<String>> header: response.headers().map().entrySet()) {
                    if (header.getKey().toLowerCase().equals(detector.response())) {
                        isCacheTarget = true;
                        this.database.put(
                            detector.getHandle(this.columns),
                            (this.setting.getPath() + path).getBytes(),
                            header.getValue().getLast().getBytes()
                        );
                    }
                }
            }
            if (isCacheTarget) {
                // TODO: chunk
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                response.body().transferTo(buf);
                this.database.put((this.setting.getPath() + path).getBytes(), buf.toByteArray());
                // TODO: content-length
                this.database.put(
                    Util.getHandle(columns, "content-length".getBytes()),
                    (this.setting.getPath() + path).getBytes(),
                    Integer.valueOf(buf.size()).toString().getBytes()
                );
            }
            this.database.flush(new FlushOptions());
        }
        return response;
    }

    private void handleDownStream(String path, HttpResponse<InputStream> upstream, HttpExchange downstream) throws RocksDBException, IOException {
        logger.debug(" <- {} {}", upstream.version(), upstream.statusCode());
        for (Entry<String, List<String>> header : upstream.headers().map().entrySet()) {
            for (String value: header.getValue()) {
                logger.debug(" <- {}: {}", header.getKey(), value);
                if (!header.getKey().toLowerCase().equals("content-length")) {
                    downstream.getResponseHeaders().add(header.getKey(), value);
                }
            }
        }
        boolean change = true;
        for (ChangeDetectionHeader detector: ChangeDetectionHeader.values()) {
            for (Entry<String,List<String>> header: downstream.getRequestHeaders().entrySet()) {
                if (detector.request().equals(header.getKey())) {
                    byte[] value = this.database.get(detector.getHandle(this.columns), (this.setting.getPath() + path).getBytes());
                    if (value != null && Arrays.equals(header.getValue().getLast().getBytes(), value)) {
                        downstream.getResponseHeaders().add(header.getKey(), header.getValue().getLast());
                        change = false;
                    }
                }
            }
        }
        // TODO: etag優先
        if (!change) {
            downstream.sendResponseHeaders(304, 0);
        } else {
            byte[] cache = this.database.get((this.setting.getPath() + path).getBytes());  // TODO: chunk
            if (cache != null) {
                downstream.sendResponseHeaders(200, cache.length);
                try (OutputStream out = downstream.getResponseBody()) {
                    out.write(cache);
                }
            } else {
                try {
                    Long length = Long.valueOf(Util.getHeader(upstream.headers().map(), "content-length"));
                    downstream.sendResponseHeaders(200, length);
                } catch (Exception e) {
                    downstream.sendResponseHeaders(200, /*HttpExchange.RSPBODY_CHUNKED*/0);
                }
                try (OutputStream out = downstream.getResponseBody()) {
                    upstream.body().transferTo(out);
                }
            }
        }
    }
}
