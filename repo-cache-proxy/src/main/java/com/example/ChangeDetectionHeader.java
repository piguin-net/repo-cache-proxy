package com.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

public enum ChangeDetectionHeader {
    LAST_MODIFIED("Last-Modified", "If-Modified-Since"),
    ETAG("ETag", "If-None-Match");

    private final String response;
    private final String request;

    private ChangeDetectionHeader(String response, String request) {
        this.response = response;
        this.request = request;
    }

    public String response() {
        return this.response.toLowerCase();
    }
    public String request() {
        return this.request.toLowerCase();
    }

    public static List<String> requests() {
        return Arrays.asList(ChangeDetectionHeader.values()).stream().map(item -> item.request()).toList();
    }
    public static List<String> responses() {
        return Arrays.asList(ChangeDetectionHeader.values()).stream().map(item -> item.response()).toList();
    }

    public static ChangeDetectionHeader valuOf(String key, Function<ChangeDetectionHeader, String> getter) {
        for (ChangeDetectionHeader value: ChangeDetectionHeader.values()) {
            if (getter.apply(value).toLowerCase().equals(key.toLowerCase())) {
                return value;
            }
        }
        return null;
    }

    public static List<ColumnFamilyDescriptor> getDescriptors() {
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        ColumnFamilyOptions option = new ColumnFamilyOptions();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, option));
        for (ChangeDetectionHeader header: ChangeDetectionHeader.values()) {
            descriptors.add(new ColumnFamilyDescriptor(header.response().getBytes(), option));
        }
        return descriptors;
    }

    public ColumnFamilyHandle getHandle(List <ColumnFamilyHandle> handles) throws RocksDBException {
        for (ColumnFamilyHandle handle: handles) {
            if (Arrays.equals(handle.getName(), this.response().getBytes())) {
                return handle;
            }
        }
        return null;
    }
}
