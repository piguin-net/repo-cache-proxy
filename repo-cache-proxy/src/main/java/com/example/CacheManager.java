package com.example;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class CacheManager {
    private static Logger logger = LoggerFactory.getLogger(CacheManager.class);

    public static class Attribute {
        public String digest;
        public String etag;
        public String lastModified;
        public Long contentLength;
        public Attribute(String digest, Map<String,List<String>> headers) {
            this.digest = digest;
            for (Entry<String,List<String>> header: headers.entrySet()) {
                switch (header.getKey().toLowerCase()) {
                    case "etag":
                        this.etag = header.getValue().getLast();
                        break;
                    case "last-modified":
                        this.lastModified = header.getValue().getLast();
                        break;
                    case "content-length":
                        this.contentLength = Long.valueOf(header.getValue().getLast());
                        break;
                }
            }
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final File dir;
    private final File database;
    private Map<String, Attribute> meta;

    public CacheManager(File dir) throws FileNotFoundException, IOException {
        this.dir = dir;
        this.dir.mkdirs();
        this.database = Path.of(this.dir.getAbsolutePath(), "meta.json").toFile();
        try {
            this.meta = this.mapper.readValue(this.database, new TypeReference<HashMap<String,Attribute>>(){});
        } catch (Exception e) {
            this.meta = new HashMap<>();
        }
    }

    public synchronized void put(String path, Map<String,List<String>> headers, InputStream data) throws FileNotFoundException, IOException {
        String digest = Util.hexdigest(path.getBytes());
        Attribute attr = new Attribute(digest, headers);
        this.meta.put(path, attr);
        this.mapper.writeValue(this.database, this.meta);
        File file = this.getFile(attr);
        File sub = file.getParentFile();
        sub.mkdirs();
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            data.transferTo(output);
        }
    }

    public synchronized Attribute get(String path) {
        return this.meta.get(path);
    }

    public synchronized Map<String, Attribute> get() {
        return this.meta;
    }

    public synchronized boolean containsKey(String path) {
        return this.meta.containsKey(path);
    }

    public synchronized void delete(String path) {
        Attribute attr = this.meta.get(path);
        File file = this.getFile(attr);
        file.delete();
        File sub = file.getParentFile();
        if (sub.listFiles().length == 0) {
            sub.delete();
        }
        this.meta.remove(path);
        this.mapper.writeValue(this.database, this.meta);
    }

    public File getFile(Attribute attr) {
        File sub = Path.of(this.dir.getAbsolutePath(), attr.digest.substring(0, 2)).toFile();
        return Path.of(sub.getAbsolutePath(), attr.digest).toFile();
    }
}
