package com.qskj.get_geo_pg.pojo;

import lombok.Data;

@Data
public class RouteResponse {
    private Long startNode;
    private Long endNode;
    private Double totalDistance;
    private String unit = "meters";
    private Object geometry; // GeoJSON Object or GeoJSON String
}
