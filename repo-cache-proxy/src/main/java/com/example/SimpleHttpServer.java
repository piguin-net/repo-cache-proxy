package com.example;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class SimpleHttpServer implements Closeable {
    private final HttpServer server;
    private final AccessLogFilter filter = new AccessLogFilter();
    private final Executor executor = new ThreadPoolDispatcher();

    public SimpleHttpServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(this.executor);
    }

    public HttpContext createContext(String path, HttpHandler handler) {
        HttpContext context = this.server.createContext(path, handler);
        context.getFilters().add(this.filter);
        return context;
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
                Util.getHeader(exchange.getRequestHeaders(), "user-agent"),
                exchange.getResponseCode(),
                Util.getHeader(exchange.getResponseHeaders(), "content-length"),
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
                Util.getHeader(exchange.getRequestHeaders(), "user-agent"),
                exchange.getResponseCode(),
                Util.getHeader(exchange.getResponseHeaders(), "content-length"),
                end - start,
                e
            );
            throw e;
        }
    }
}