package com.qskj.get_geo_pg.pojo;

import lombok.Data;

@Data
public class RoadNetworkConfig {
    private String id;
    private String name;
    private String roadTable;
    private String nodedTable;
    private Double centerLng;
    private Double centerLat;
    private Integer defaultZoom;
    private Integer sortOrder;
    private Integer status;
    private String buildTime;
}
