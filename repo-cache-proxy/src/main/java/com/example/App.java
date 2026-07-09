package com.example;

import java.io.Closeable;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.handler.CacheHandler;
import com.example.handler.SystemHandler;

/**
 * Hello world!
 *
 */
public class App implements Closeable
{
    private static Logger logger = LoggerFactory.getLogger(App.class);

    public static void main( String[] args ) throws RocksDBException, IOException
    {
        Setting setting = new Setting();
        logger.info("app start. {}", setting.toString());

        App app = new App(setting);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.close();
            } catch (Exception e) {
                logger.error("app exit error.", e);
            }
        }));
        app.start();
    }

    private final Setting setting;
    private final RocksDB database;  // TODO: delete=>jackson
    private final HttpClient http;
    private final DBOptions options = new DBOptions();
    private final List<ColumnFamilyHandle> columns = new ArrayList<>();
    private final SimpleHttpServer server;

    public App(Setting setting) throws RocksDBException, IOException {
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

        SystemHandler systemHandler = new SystemHandler(this.database, this.columns);
        CacheHandler cacheHandler = new CacheHandler(this.setting, this.database, this.http, this.columns);

        this.server = new SimpleHttpServer(setting.getPort());
        this.server.createContext("/", systemHandler.index());
        this.server.createContext("/metadata", systemHandler.metadata());
        this.server.createContext(setting.getPath(), cacheHandler);
    }

    public void start() {
        this.server.start();
    }

    @Override
    public void close() {
        this.database.close();
        this.options.close();
    }
}
