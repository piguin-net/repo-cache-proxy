package com.example;

import java.nio.file.Path;

import lombok.Getter;
import lombok.ToString;

@ToString
@Getter
public class Setting {
    private Integer port;
    private String match;
    private String upstream;
    private Path database;
    public Setting() {
        this.port = Integer.getInteger("port", 1324);
        this.match = System.getProperty("match");
        this.upstream = System.getProperty("upstream");
        this.database = Path.of(System.getProperty("database", "cache"));
    }
}
 