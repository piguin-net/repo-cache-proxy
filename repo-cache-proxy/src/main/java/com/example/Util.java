package com.example;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;

public class Util {
    public static String getHeader(Map<String, List<String>> headers, String target) {
        for (Entry<String, List<String>> entry: headers.entrySet()) {
            if (entry.getKey().toLowerCase().equals(target.toLowerCase())) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }
    public static ColumnFamilyHandle getHandle(List<ColumnFamilyHandle> handles, byte[] name) throws RocksDBException {
        for (ColumnFamilyHandle handle: handles) {
            if (Arrays.equals(handle.getName(), name)) {
                return handle;
            }
        }
        return null;
    }
}
