package com.qskj.get_geo_pg.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PgrbFileManage {
    private Integer id;
    private String networkId;
    private String networkName;
    private String level;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileSizeFmt;
    private Integer nodeCount;
    private Integer edgeCount;
    private Integer pointCount;
    private Integer boundaryPointCount;
    private Double minLng;
    private Double minLat;
    private Double maxLng;
    private Double maxLat;
    private Double centerLng;
    private Double centerLat;
    private Integer defaultZoom;
    private Integer status;
    private String buildTime;
    private String updatedAt;
    private String remark;
}
