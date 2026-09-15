package com.qskj.get_geo_pg.mapper;

import com.qskj.get_geo_pg.pojo.Bridge;
import com.qskj.get_geo_pg.pojo.River;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GeoMapper {


    @Select("SELECT gid as id, rivid as rivId, name, curlen as curLen, area, flow, pcdm, spcounty as spCounty, epcounty as epCounty, level as rivLv, crotype as croType, county, flowriv as flowRiv, flowid as flowId, ST_AsGeoJSON(geom) as geometry " +
            "FROM ya_data.rivers " +
            "WHERE ST_Intersects(geom, ST_MakeEnvelope(#{minLng}, #{minLat}, #{maxLng}, #{maxLat}, 4326))")
    List<River> getYaRiverByBbox(@org.apache.ibatis.annotations.Param("minLng") double minLng, 
                                 @org.apache.ibatis.annotations.Param("minLat") double minLat, 
                                 @org.apache.ibatis.annotations.Param("maxLng") double maxLng, 
                                 @org.apache.ibatis.annotations.Param("maxLat") double maxLat);

    @Select("SELECT gid, name as bridgeName, ST_AsGeoJSON(geom) as geometry from ya_data.bridges")
    List<Bridge> getYaBridges();

    @Select("WITH RECURSIVE river_path AS (" +
            "    SELECT * FROM (" +
            "        SELECT gid, rivid, name, curlen, area, flow, pcdm, spcounty, epcounty, level, crotype, county, flowriv, flowid, geom, ARRAY[rivid] as path "
            +
            "        FROM ya_data.rivers " +
            "        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326) " +
            "        LIMIT 1" +
            "    ) AS base_case" +
            "    UNION ALL " +
            "    SELECT r.gid, r.rivid, r.name, r.curlen, r.area, r.flow, r.pcdm, r.spcounty, r.epcounty, r.level, r.crotype, r.county, r.flowriv, r.flowid, r.geom, rp.path || r.rivid "
            +
            "    FROM ya_data.rivers r " +
            "    INNER JOIN river_path rp ON r.rivid = rp.flowid " +
            "    WHERE r.rivid <> ALL(rp.path)" +
            ") " +
            "SELECT gid as id, rivid as rivId, name, curlen as curLen, area, flow, pcdm, spcounty as spCounty, epcounty as epCounty, level as rivLv, crotype as croType, county, flowriv as flowRiv, flowid as flowId, ST_AsGeoJSON(geom) as geometry "
            +
            "FROM river_path")
    List<River> getRiverRouteAll(@org.apache.ibatis.annotations.Param("lng") double lng,
            @org.apache.ibatis.annotations.Param("lat") double lat);

    @Select("WITH RECURSIVE river_path AS (" +
            "    SELECT * FROM (" +
            "        SELECT gid, rivid, name, curlen, area, flow, pcdm, spcounty, epcounty, level, crotype, county, flowriv, flowid, geom, " +
            "               ST_LineSubstring(ST_GeometryN(geom, 1), ST_LineLocatePoint(ST_GeometryN(geom, 1), ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326)), 1) as intersect_geom, " +
            "               ARRAY[rivid] as path " +
            "        FROM ya_data.rivers " +
            "        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326) " +
            "        LIMIT 1" +
            "    ) AS base_case" +
            "    UNION ALL " +
            "    SELECT r.gid, r.rivid, r.name, r.curlen, r.area, r.flow, r.pcdm, r.spcounty, r.epcounty, r.level, r.crotype, r.county, r.flowriv, r.flowid, r.geom, " +
            "           ST_LineSubstring(ST_GeometryN(r.geom, 1), ST_LineLocatePoint(ST_GeometryN(r.geom, 1), ST_ClosestPoint(ST_GeometryN(r.geom, 1), ST_GeometryN(rp.geom, 1))), 1) as intersect_geom, " +
            "           rp.path || r.rivid " +
            "    FROM ya_data.rivers r " +
            "    INNER JOIN river_path rp ON r.rivid = rp.flowid " +
            "    WHERE r.rivid <> ALL(rp.path)" +
            ") " +
            "SELECT gid as id, rivid as rivId, name, curlen as curLen, area, flow, pcdm, spcounty as spCounty, epcounty as epCounty, level as rivLv, crotype as croType, county, flowriv as flowRiv, flowid as flowId, ST_AsGeoJSON(intersect_geom) as geometry " +
            "FROM river_path " +
            "WHERE intersect_geom IS NOT NULL AND NOT ST_IsEmpty(intersect_geom)")
    List<River> getRiverRoute(@org.apache.ibatis.annotations.Param("lng") double lng, @org.apache.ibatis.annotations.Param("lat") double lat);

    @Select("SELECT current_setting('data_directory')")
    String getPgDataDirectory();

    @Select("SELECT pg_relation_filepath(i.indexrelid) FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_class ic ON ic.oid = i.indexrelid JOIN pg_am am ON am.oid = ic.relam WHERE (n.nspname = 'ya_data' OR n.nspname = 'public') AND (c.relname = 'rivers' OR c.relname = 'ya_river') AND am.amname = 'gist' AND pg_relation_filepath(i.indexrelid) IS NOT NULL LIMIT 1")
    String getYaRiverGistIndexFilePath();

    @Select("SELECT encode(pg_read_binary_file(pg_relation_filepath(i.indexrelid)), 'hex') FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_class ic ON ic.oid = i.indexrelid JOIN pg_am am ON am.oid = ic.relam WHERE (n.nspname = 'ya_data' OR n.nspname = 'public') AND (c.relname = 'rivers' OR c.relname = 'ya_river') AND am.amname = 'gist' AND pg_relation_filepath(i.indexrelid) IS NOT NULL LIMIT 1")
    String getYaRiverGistIndexHex();

    default byte[] getYaRiverGistIndexBytes() {
        String hex = getYaRiverGistIndexHex();
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        if (hex.startsWith("\\x") || hex.startsWith("\\X")) {
            hex = hex.substring(2);
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    @Select("SELECT gid as id, rivid as rivId, name, curlen as curLen, area, flow, pcdm, spcounty as spCounty, epcounty as epCounty, level as rivLv, crotype as croType, county, flowriv as flowRiv, flowid as flowId, ST_AsGeoJSON(geom) as geometry FROM ya_data.rivers WHERE ctid = format('(%s,%s)', #{blkid}, #{posid})::tid")
    River getYaRiverByTid(@org.apache.ibatis.annotations.Param("blkid") int blkid, @org.apache.ibatis.annotations.Param("posid") int posid);
}
