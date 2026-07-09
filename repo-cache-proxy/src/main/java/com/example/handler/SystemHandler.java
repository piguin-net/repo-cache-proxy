package com.example.handler;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.ChangeDetectionHeader;
import com.sun.net.httpserver.HttpHandler;

import tools.jackson.databind.ObjectMapper;

public class SystemHandler {
    private static Logger logger = LoggerFactory.getLogger(SystemHandler.class);

    private final RocksDB database;
    private final List<ColumnFamilyHandle> columns;
    private final ObjectMapper mapper = new ObjectMapper();

    public SystemHandler(RocksDB database, List<ColumnFamilyHandle> columns) {
        this.database = database;
        this.columns = columns;
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
            SortedMap<String,SortedMap<String,String>> entries = new TreeMap<>();
            try (
                ReadOptions options = new ReadOptions();
                RocksIterator iterator = this.database.newIterator(options);
            ) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    ChangeDetectionHeader.getDescriptors();
                    byte[] key = iterator.key();
                    SortedMap<String,String> detail = new TreeMap<>();
                    for (ColumnFamilyHandle column: columns) {
                        if (!Arrays.equals(RocksDB.DEFAULT_COLUMN_FAMILY, column.getName())) {
                            byte[] value = this.database.get(column, key);
                            detail.put(new String(column.getName()), value != null ? new String(value) : null);
                        }
                    }
                    entries.put(new String(key), detail);
                }
            } catch (RocksDBException e) {
                e.printStackTrace();
            }
            byte[] response = this.mapper.writeValueAsString(entries).getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseHeaders().add("content-type", "application/json");
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        };
    }
}
