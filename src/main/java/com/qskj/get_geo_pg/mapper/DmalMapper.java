package com.qskj.get_geo_pg.mapper;

import com.qskj.get_geo_pg.pojo.Dmal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DmalMapper {
    @Select("SELECT gid as id, \"名称\" as name, ST_AsGeoJSON(geom) as geometry " +
            "FROM hhgl.dmal " +
            "WHERE geom && ST_Expand(ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326), #{distance} / 70.0) " +
            "AND ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326)::geography, #{distance} * 1000) " +
            "ORDER BY geom <-> ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326)")
    List<Dmal> getNearestDmal(@Param("lng") double lng, @Param("lat") double lat, @Param("distance") int distance);
}
