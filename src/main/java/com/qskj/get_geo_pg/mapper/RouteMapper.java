package com.qskj.get_geo_pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface RouteMapper {

    @Select("WITH route AS (" +
            "    SELECT r.seq, r.node, r.edge, r.cost, r.agg_cost, e.geom " +
            "    FROM pgr_dijkstra(" +
            "        'SELECT id, source, target, cost, reverse_cost FROM road.shjd_base_noded', " +
            "        #{startNode}, #{endNode}, #{directed} " +
            "    ) AS r " +
            "    JOIN road.shjd_base_noded AS e ON r.edge = e.id " +
            "    ORDER BY r.seq " +
            ") " +
            "SELECT " +
            "    COALESCE(SUM(cost), 0) AS total_distance, " +
            "    ST_AsGeoJSON(ST_Union(geom)) AS route_geojson " +
            "FROM route")
    Map<String, Object> computeRoute(
            @Param("startNode") long startNode,
            @Param("endNode") long endNode,
            @Param("directed") boolean directed);
}
