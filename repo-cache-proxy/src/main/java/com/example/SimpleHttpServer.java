package com.example;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class SimpleHttpServer implements Closeable {
    private HttpServer server;

    public SimpleHttpServer(int port, HttpHandler handler) throws IOException {
        this.server = HttpServer.create(
            new InetSocketAddress(port),
            0,
            "/",
            handler,
            new AccessLogFilter()
        );
        this.server.setExecutor(new ThreadPoolDispatcher());
    }

    public void start() {
        this.server.start();
    }

    @Override
    public void close() throws IOException {
        this.server.stop(0);
    }
}

class ThreadPoolDispatcher implements Executor {
    private ExecutorService pool = Executors.newCachedThreadPool();
    @Override
    public void execute(Runnable command) {
        this.pool.execute(command);
    }
}

class CacheProxyHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (OutputStream response = exchange.getResponseBody()) {
            String url = exchange.getRequestURI().normalize().toString();
            exchange.sendResponseHeaders(200, url.length());
            response.write(url.getBytes());
        }
    }
}

class AccessLogFilter extends Filter {
    private static Logger logger = LoggerFactory.getLogger(AccessLogFilter.class);
    @Override
    public String description() {
        return this.getClass().getSimpleName();
    }
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        long start = new Date().getTime();
        try {
            chain.doFilter(exchange);
            long end = new Date().getTime();
            logger.info(
                "{}:{} {} {} {} {} {} {} {}",
                exchange.getRemoteAddress().getAddress().getHostAddress(),
                exchange.getRemoteAddress().getPort(),
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getProtocol(),
                getHeader(exchange.getRequestHeaders(), "user-agent"),
                exchange.getResponseCode(),
                getHeader(exchange.getResponseHeaders(), "content-length"),
                end - start
            );
        } catch (Throwable e) {
            long end = new Date().getTime();
            logger.error(
                "{}:{} {} {} {} {} {} {} {}",
                exchange.getRemoteAddress().getAddress().getHostAddress(),
                exchange.getRemoteAddress().getPort(),
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getProtocol(),
                getHeader(exchange.getRequestHeaders(), "user-agent"),
                exchange.getResponseCode(),
                getHeader(exchange.getResponseHeaders(), "content-length"),
                end - start,
                e
            );
            throw e;
        }
    }
    public static String getHeader(Map<String, List<String>> headers, String target) {
        for (Entry<String, List<String>> entry: headers.entrySet()) {
            if (entry.getKey().toLowerCase().equals(target.toLowerCase())) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }
}