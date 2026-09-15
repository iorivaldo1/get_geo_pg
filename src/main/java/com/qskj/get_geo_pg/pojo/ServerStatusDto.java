package com.qskj.get_geo_pg.pojo;

import lombok.Data;
import java.util.List;

@Data
public class ServerStatusDto {
    private ServerInfo server;
    private SpringBootInfo springboot;
    private TomcatInfo tomcat;
    private GeoServerInfo geoserver;

    @Data
    public static class ServerInfo {
        private String state;
        private int cpu;
        private int memory;
        private String totalMemoryStr;
        private String usedMemoryStr;
        private String uptime;
    }

    @Data
    public static class SpringBootInfo {
        private String state;
        private int latency;
        private int connections;
        private int memory;
        private List<JavaProcess> processes;
    }

    @Data
    public static class JavaProcess {
        private long pid;
        private String name;
    }

    @Data
    public static class TomcatInfo {
        private String state;
        private int threads;
        private int tps;
        private int sessions;
        private List<AppInfo> apps;
    }

    @Data
    public static class AppInfo {
        private String name;
        private String status;
    }

    @Data
    public static class GeoServerInfo {
        private String state;
        private int wmsLatency;
        private String cache;
        private String gpwCache;
        private int processing;
    }
}
