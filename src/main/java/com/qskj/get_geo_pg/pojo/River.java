package com.qskj.get_geo_pg.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class River {
    private Integer id;
    private Integer rivId;
    private String name;
    private String curLen;
    private String area;
    private String flow;
    private String pcdm;
    private String spCounty;
    private String epCounty;
    private Integer rivLv;
    private String croType;
    private String geometry;
    private String city;
    private String county;
    private String village;
    private String flowRiv;
    private Integer flowId;

}
