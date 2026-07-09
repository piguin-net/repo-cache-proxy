package com.example;

import java.nio.file.Path;

import lombok.Getter;
import lombok.ToString;

@ToString
@Getter
public class Setting {
    private Integer port;
    private String path;
    private String upstream;
    private Path database;
    public Setting() {
        this.port = Integer.getInteger("port", 1324);
        this.path = System.getProperty("path");
        this.upstream = System.getProperty("upstream");
        this.database = Path.of(System.getProperty("database", "cache"));
    }
}
 