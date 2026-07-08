package com.example;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Hello world!
 *
 */
public class App implements HttpHandler, Closeable
{
    private static Logger logger = LoggerFactory.getLogger(App.class);

    public static void main( String[] args ) throws RocksDBException, IOException
    {
        Setting setting = new Setting();
        logger.info(setting.toString());

        App app = new App(setting);

        SimpleHttpServer server = new SimpleHttpServer(
            setting.getPort(),
            app
        );
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
                app.close();
            } catch (Exception e) {
                logger.error("server stop error.", e);
            }
        }));
    }

    private final Setting setting;
    private final RocksDB database;
    private final HttpClient http;
    private final DBOptions options = new DBOptions();
    private final List<ColumnFamilyHandle> columns = new ArrayList<>();

    public App(Setting setting) throws RocksDBException {
        this.setting = setting;
        this.options.setCreateIfMissing(true);
        this.options.setCreateMissingColumnFamilies(true);
        this.database = RocksDB.open(
            this.options,
            setting.getDatabase().toString(),
            ChangeDetectionHeader.getDescriptors(),
            this.columns
        );
        this.http = HttpClient.newBuilder()
            .version(Version.HTTP_1_1)
            .followRedirects(Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();
    }

    @Override
    public void handle(HttpExchange downstream) throws IOException {
        try {
            String path = downstream.getRequestURI().toString();
            if (path.startsWith(this.setting.getMatch())) {
                String url = this.getUrl(downstream);
                HttpResponse<InputStream> upstream = this.handleUpStream(url, downstream);
                if (upstream.statusCode() < 400) {
                    this.handleDownStream(url, upstream, downstream);
                } else {
                    logger.warn("upstream error response. ({})", upstream.statusCode());
                    try {
                        Long length = Long.valueOf(AccessLogFilter.getHeader(upstream.headers().map(), "content-length"));
                        downstream.sendResponseHeaders(upstream.statusCode(), length);
                        try (OutputStream out = downstream.getResponseBody()) {
                            upstream.body().transferTo(out);
                        }
                    } catch (Exception e) {
                        downstream.sendResponseHeaders(upstream.statusCode(), /*HttpExchange.RSPBODY_CHUNKED*/0);
                        downstream.getResponseBody().close();
                    }
                }
            } else {
                logger.warn("url not match. ({})", path);
                downstream.sendResponseHeaders(404, 0);
                downstream.getResponseBody().close();
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new IOException(e);
        }
    }

    private String getUrl(HttpExchange downstream) {
        String path = downstream.getRequestURI().toString();
        return this.setting.getUpstream() + path.substring(this.setting.getMatch().length());
    }

    private HttpResponse<InputStream> handleUpStream(String url, HttpExchange downstream) throws IOException, InterruptedException, RocksDBException {
        Builder request = HttpRequest.newBuilder().uri(URI.create(url)).GET();
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
            byte[] value = this.database.get(detector.getHandle(this.columns), url.getBytes());
            if (value != null && !downstream.getRequestHeaders().containsKey(detector.request())) {
                    logger.debug(" ->> {}: {}", detector.request(), new String(value));
                    request.header(detector.request(), new String(value));
            }
        }
        HttpResponse<InputStream> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 200) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            response.body().transferTo(buf);
            this.database.put(url.getBytes(), buf.toByteArray());
            for (ChangeDetectionHeader detector: ChangeDetectionHeader.values()) {
                for (Entry<String,List<String>> header: response.headers().map().entrySet()) {
                    if (header.getKey().toLowerCase().equals(detector.response())) {
                        this.database.put(
                            detector.getHandle(this.columns),
                            url.getBytes(),
                            header.getValue().getLast().getBytes()
                        );
                    }
                }
            }
            this.database.flush(new FlushOptions());
        }
        return response;
    }

    private void handleDownStream(String url, HttpResponse<InputStream> upstream, HttpExchange downstream) throws RocksDBException, IOException {
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
                    byte[] value = this.database.get(detector.getHandle(this.columns), url.getBytes());
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
            byte[] cache = this.database.get(url.getBytes());  // TODO: chunk
            downstream.sendResponseHeaders(200, cache.length);
            try (OutputStream out = downstream.getResponseBody()) {
                out.write(cache);
            }
        }
    }

    @Override
    public void close() {
        this.database.close();
        this.options.close();
    }
}
