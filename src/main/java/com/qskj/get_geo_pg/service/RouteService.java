package com.qskj.get_geo_pg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qskj.get_geo_pg.pojo.RoadNetworkConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RouteService {

    @Autowired
    @Qualifier("graphsJdbcTemplate")
    private JdbcTemplate graphsJdbcTemplate;

    @Autowired
    @Qualifier("jdbcTemplate")
    private JdbcTemplate primaryJdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void ensureRoadSchemaAndTable() {
        try {
            graphsJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS upload_shp_road;");
            graphsJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS upload_shp_road.sys_road_network (" +
                    "id VARCHAR(64) PRIMARY KEY, " +
                    "name VARCHAR(128) NOT NULL, " +
                    "road_table VARCHAR(128) NOT NULL, " +
                    "noded_table VARCHAR(128) NOT NULL, " +
                    "center_lng DOUBLE PRECISION, " +
                    "center_lat DOUBLE PRECISION, " +
                    "default_zoom INT DEFAULT 15, " +
                    "sort_order INT DEFAULT 10, " +
                    "status INT DEFAULT 1" +
                    ");");
            graphsJdbcTemplate.execute("ALTER TABLE upload_shp_road.sys_road_network ADD COLUMN IF NOT EXISTS build_time TIMESTAMP DEFAULT NOW();");
            autoMigrateOldRoadTableNames();
        } catch (Exception e) {
            try {
                graphsJdbcTemplate.execute("ROLLBACK;");
            } catch (Exception ignored) {
            }
        }
    }

    private void autoMigrateOldRoadTableNames() {
        try {
            graphsJdbcTemplate.execute(
                    "UPDATE xzq_road.sys_road_network_by_xzq SET " +
                            "road_table = REPLACE(road_table, 'road.', 'xzq_road.'), " +
                            "noded_table = REPLACE(noded_table, 'road.', 'xzq_road.') " +
                            "WHERE road_table LIKE 'road.%' OR noded_table LIKE 'road.%';");
        } catch (Exception ignored) {
        }
        try {
            graphsJdbcTemplate.execute(
                    "UPDATE xzq_road.sys_road_network SET " +
                            "road_table = REPLACE(road_table, 'road.', 'xzq_road.'), " +
                            "noded_table = REPLACE(noded_table, 'road.', 'xzq_road.') " +
                            "WHERE road_table LIKE 'road.%' OR noded_table LIKE 'road.%';");
        } catch (Exception ignored) {
        }
        try {
            graphsJdbcTemplate.execute(
                    "UPDATE upload_shp_road.sys_road_network SET " +
                            "road_table = REPLACE(road_table, 'road.', 'upload_shp_road.'), " +
                            "noded_table = REPLACE(noded_table, 'road.', 'upload_shp_road.') " +
                            "WHERE road_table LIKE 'road.%' OR noded_table LIKE 'road.%';");
        } catch (Exception ignored) {
        }
    }

    public String resolveActualTable(String tableName) {
        if (tableName == null || tableName.trim().isEmpty())
            return tableName;
        if (tableName.startsWith("road.")) {
            String bareName = tableName.substring(5);
            if (tableExists("xzq_road", bareName)) {
                return "xzq_road." + bareName;
            }
            if (tableExists("upload_shp_road", bareName)) {
                return "upload_shp_road." + bareName;
            }
        }
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            String schemaPart = parts[0].replace("\"", "");
            if (tableExists(schemaPart, parts[1])) {
                return schemaPart.equals("3d_road") ? "\"3d_road\"." + parts[1] : tableName;
            }
            if (tableExists("3d_road", parts[1])) {
                return "\"3d_road\"." + parts[1];
            }
            if (tableExists("xzq_road", parts[1])) {
                return "xzq_road." + parts[1];
            }
            if (tableExists("upload_shp_road", parts[1])) {
                return "upload_shp_road." + parts[1];
            }
        } else {
            if (tableExists("3d_road", tableName)) {
                return "\"3d_road\"." + tableName;
            }
            if (tableExists("xzq_road", tableName)) {
                return "xzq_road." + tableName;
            }
            if (tableExists("upload_shp_road", tableName)) {
                return "upload_shp_road." + tableName;
            }
        }
        return tableName;
    }

    public boolean tableExists(String schema, String table) {
        try {
            String cleanSchema = schema != null ? schema.replace("\"", "") : "";
            String cleanTable = table != null ? table.replace("\"", "") : "";
            Integer count = graphsJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class, cleanSchema, cleanTable);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取数据库中配置的所有有效路网 (合并 upload_shp_road 与 xzq_road 与 3d_road)
     */
    public List<RoadNetworkConfig> getAllNetworks() {
        return getAllNetworks("all");
    }

    /**
     * 按指定模式获取路网配置列表
     * @param mode 可选值: "2d" (行政区划2D), "3d" (立体分层), "shp" (上传shp), "all" (全部)
     */
    public List<RoadNetworkConfig> getAllNetworks(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            mode = "all";
        }
        String m = mode.trim().toLowerCase();
        boolean need3d = m.contains("3d") || m.equals("all");
        boolean needShp = m.contains("shp") || m.contains("upload") || m.equals("all");
        boolean need2d = m.contains("2d") || m.contains("xzq") || m.equals("all");

        List<RoadNetworkConfig> results = new java.util.ArrayList<>();
        Set<String> seenIds = new java.util.HashSet<>();

        // 0. 查询 "3d_road".sys_road_network_by_xzq
        if (need3d) {
            try {
                String sql = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                        + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                        + "FROM \"3d_road\".sys_road_network_by_xzq WHERE status = 1 ORDER BY sort_order ASC, id ASC";
                List<RoadNetworkConfig> d3List = graphsJdbcTemplate.query(sql, (rs, rowNum) -> {
                    RoadNetworkConfig config = new RoadNetworkConfig();
                    config.setId(rs.getString("id"));
                    config.setName(rs.getString("name"));
                    config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                    config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                    config.setCenterLng(rs.getDouble("center_lng"));
                    config.setCenterLat(rs.getDouble("center_lat"));
                    config.setDefaultZoom(rs.getInt("default_zoom"));
                    config.setSortOrder(rs.getInt("sort_order"));
                    config.setStatus(rs.getInt("status"));
                    config.setBuildTime(rs.getString("build_time"));
                    return config;
                });
                for (RoadNetworkConfig c : d3List) {
                    if (seenIds.add(c.getId())) {
                        results.add(c);
                    }
                }
            } catch (Exception ignored) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignore) {
                }
            }
        }

        // 1. 查询 upload_shp_road.sys_road_network
        if (needShp) {
            try {
                ensureRoadSchemaAndTable();
                String sql = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                        + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                        + "FROM upload_shp_road.sys_road_network WHERE status = 1 ORDER BY sort_order ASC";
                List<RoadNetworkConfig> shpList = graphsJdbcTemplate.query(sql, (rs, rowNum) -> {
                    RoadNetworkConfig config = new RoadNetworkConfig();
                    config.setId(rs.getString("id"));
                    config.setName(rs.getString("name"));
                    config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                    config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                    config.setCenterLng(rs.getDouble("center_lng"));
                    config.setCenterLat(rs.getDouble("center_lat"));
                    config.setDefaultZoom(rs.getInt("default_zoom"));
                    config.setSortOrder(rs.getInt("sort_order"));
                    config.setStatus(rs.getInt("status"));
                    config.setBuildTime(rs.getString("build_time"));
                    return config;
                });
                for (RoadNetworkConfig c : shpList) {
                    if (seenIds.add(c.getId())) {
                        results.add(c);
                    }
                }
            } catch (Exception ignored) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignore) {
                }
            }
        }

        // 2. 查询 xzq_road.sys_road_network_by_xzq
        if (need2d) {
            try {
                String sql = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                        + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                        + "FROM xzq_road.sys_road_network_by_xzq WHERE status = 1 ORDER BY sort_order ASC, id ASC";
                List<RoadNetworkConfig> xzqList = graphsJdbcTemplate.query(sql, (rs, rowNum) -> {
                    RoadNetworkConfig config = new RoadNetworkConfig();
                    config.setId(rs.getString("id"));
                    config.setName(rs.getString("name"));
                    config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                    config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                    config.setCenterLng(rs.getDouble("center_lng"));
                    config.setCenterLat(rs.getDouble("center_lat"));
                    config.setDefaultZoom(rs.getInt("default_zoom"));
                    config.setSortOrder(rs.getInt("sort_order"));
                    config.setStatus(rs.getInt("status"));
                    config.setBuildTime(rs.getString("build_time"));
                    return config;
                });
                for (RoadNetworkConfig c : xzqList) {
                    if (seenIds.add(c.getId())) {
                        results.add(c);
                    }
                }
            } catch (Exception ignored) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignore) {
                }
            }

            // 3. 兼容查询 xzq_road.sys_road_network
            try {
                String sql = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                        + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                        + "FROM xzq_road.sys_road_network WHERE status = 1 ORDER BY sort_order ASC, id ASC";
                List<RoadNetworkConfig> xzqList = graphsJdbcTemplate.query(sql, (rs, rowNum) -> {
                    RoadNetworkConfig config = new RoadNetworkConfig();
                    config.setId(rs.getString("id"));
                    config.setName(rs.getString("name"));
                    config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                    config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                    config.setCenterLng(rs.getDouble("center_lng"));
                    config.setCenterLat(rs.getDouble("center_lat"));
                    config.setDefaultZoom(rs.getInt("default_zoom"));
                    config.setSortOrder(rs.getInt("sort_order"));
                    config.setStatus(rs.getInt("status"));
                    config.setBuildTime(rs.getString("build_time"));
                    return config;
                });
                for (RoadNetworkConfig c : xzqList) {
                    if (seenIds.add(c.getId())) {
                        results.add(c);
                    }
                }
            } catch (Exception ignored) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignore) {
                }
            }
        }

        if (results.isEmpty() && (m.equals("all") || m.contains("shp"))) {
            // 当配置表未初始化且包含 shp 时默认退回路网
            RoadNetworkConfig defaultConfig = new RoadNetworkConfig();
            defaultConfig.setId("shjd_road");
            defaultConfig.setName("石景山/标准路网数据");
            defaultConfig.setRoadTable("upload_shp_road.shjd_base");
            defaultConfig.setNodedTable("upload_shp_road.shjd_base_noded");
            defaultConfig.setCenterLng(104.114000);
            defaultConfig.setCenterLat(30.632000);
            defaultConfig.setDefaultZoom(16);
            defaultConfig.setSortOrder(1);
            defaultConfig.setStatus(1);
            return Collections.singletonList(defaultConfig);
        }

        return results;
    }

    /**
     * 根据 networkId 检索匹配的配置（优先 upload_shp_road，兼容 xzq_road 与默认兜底）
     */
    public RoadNetworkConfig getNetworkConfig(String networkId) {
        List<RoadNetworkConfig> list = getAllNetworks();
        if (networkId != null && !networkId.trim().isEmpty()) {
            for (RoadNetworkConfig cfg : list) {
                if (networkId.equalsIgnoreCase(cfg.getId())) {
                    return cfg;
                }
            }

            // 0. 尝试在 "3d_road".sys_road_network_by_xzq 中检索
            try {
                List<RoadNetworkConfig> d3List = graphsJdbcTemplate.query(
                        "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status "
                                + "FROM \"3d_road\".sys_road_network_by_xzq WHERE id = ?",
                        (rs, rowNum) -> {
                            RoadNetworkConfig config = new RoadNetworkConfig();
                            config.setId(rs.getString("id"));
                            config.setName(rs.getString("name"));
                            config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                            config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                            config.setCenterLng(rs.getDouble("center_lng"));
                            config.setCenterLat(rs.getDouble("center_lat"));
                            config.setDefaultZoom(rs.getInt("default_zoom"));
                            config.setSortOrder(rs.getInt("sort_order"));
                            config.setStatus(rs.getInt("status"));
                            return config;
                        }, networkId);
                if (d3List != null && !d3List.isEmpty()) {
                    return d3List.get(0);
                }
            } catch (Exception ignored) {
            }

            // 1. 尝试在 xzq_road.sys_road_network_by_xzq 中检索
            try {
                List<RoadNetworkConfig> xzqList = graphsJdbcTemplate.query(
                        "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status "
                                + "FROM xzq_road.sys_road_network_by_xzq WHERE id = ?",
                        (rs, rowNum) -> {
                            RoadNetworkConfig config = new RoadNetworkConfig();
                            config.setId(rs.getString("id"));
                            config.setName(rs.getString("name"));
                            config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                            config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                            config.setCenterLng(rs.getDouble("center_lng"));
                            config.setCenterLat(rs.getDouble("center_lat"));
                            config.setDefaultZoom(rs.getInt("default_zoom"));
                            config.setSortOrder(rs.getInt("sort_order"));
                            config.setStatus(rs.getInt("status"));
                            return config;
                        }, networkId);
                if (xzqList != null && !xzqList.isEmpty()) {
                    return xzqList.get(0);
                }
            } catch (Exception ignored) {
            }

            // 2. 尝试在 xzq_road.sys_road_network 中检索
            try {
                List<RoadNetworkConfig> xzqList = graphsJdbcTemplate.query(
                        "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status "
                                + "FROM xzq_road.sys_road_network WHERE id = ?",
                        (rs, rowNum) -> {
                            RoadNetworkConfig config = new RoadNetworkConfig();
                            config.setId(rs.getString("id"));
                            config.setName(rs.getString("name"));
                            config.setRoadTable(resolveActualTable(rs.getString("road_table")));
                            config.setNodedTable(resolveActualTable(rs.getString("noded_table")));
                            config.setCenterLng(rs.getDouble("center_lng"));
                            config.setCenterLat(rs.getDouble("center_lat"));
                            config.setDefaultZoom(rs.getInt("default_zoom"));
                            config.setSortOrder(rs.getInt("sort_order"));
                            config.setStatus(rs.getInt("status"));
                            return config;
                        }, networkId);
                if (xzqList != null && !xzqList.isEmpty()) {
                    return xzqList.get(0);
                }
            } catch (Exception ignored) {
            }

            // 动态支持任意 upload_shp_road 或 xzq_road 或 3d_road 自定义 networkId
            RoadNetworkConfig dynConfig = new RoadNetworkConfig();
            dynConfig.setId(networkId);
            dynConfig.setName(networkId);
            if (networkId.endsWith("_3d") || networkId.contains("3d_")) {
                dynConfig.setRoadTable("\"3d_road\"." + networkId + "_base");
                dynConfig.setNodedTable("\"3d_road\"." + networkId + "_base_noded");
            } else if (networkId.startsWith("xzq_")) {
                dynConfig.setRoadTable("xzq_road." + networkId + "_base");
                dynConfig.setNodedTable("xzq_road." + networkId + "_base_noded");
            } else {
                dynConfig.setRoadTable("upload_shp_road." + networkId + "_base");
                dynConfig.setNodedTable("upload_shp_road." + networkId + "_base_noded");
            }
            return dynConfig;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除指定路网及其物理表和配置记录
     */
    public boolean deleteRoadNetwork(String networkId) {
        if (networkId == null || networkId.trim().isEmpty()) {
            throw new IllegalArgumentException("删除失败：路网 ID 不能为空");
        }

        RoadNetworkConfig config = getNetworkConfig(networkId);
        if (config == null || !networkId.equalsIgnoreCase(config.getId())) {
            throw new IllegalArgumentException("未找到待删除的路网配置: " + networkId);
        }

        String roadTable = config.getRoadTable();
        String nodedTable = config.getNodedTable();

        // 1. 删除关联物理表
        if (nodedTable != null && !nodedTable.trim().isEmpty()) {
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + "_vertices_pgr CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + " CASCADE;");
        }
        if (roadTable != null && !roadTable.trim().isEmpty()) {
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + roadTable + " CASCADE;");
        }

        // 2. 删除配置记录
        int deletedRows = 0;
        try {
            deletedRows = graphsJdbcTemplate.update("DELETE FROM \"3d_road\".sys_road_network_by_xzq WHERE id = ?", networkId);
        } catch (Exception ignored) {}
        if (deletedRows == 0) {
            deletedRows = graphsJdbcTemplate.update("DELETE FROM upload_shp_road.sys_road_network WHERE id = ?", networkId);
        }
        if (deletedRows == 0) {
            try {
                deletedRows = graphsJdbcTemplate.update("DELETE FROM xzq_road.sys_road_network_by_xzq WHERE id = ?", networkId);
            } catch (Exception ignored) {}
        }
        if (deletedRows == 0) {
            try {
                deletedRows = graphsJdbcTemplate.update("DELETE FROM xzq_road.sys_road_network WHERE id = ?", networkId);
            } catch (Exception ignored) {}
        }
        return deletedRows > 0;
    }

    /**
     * 更新指定路网的显示名称
     */
    public boolean updateNetworkName(String networkId, String newName) {
        if (networkId == null || networkId.trim().isEmpty()) {
            throw new IllegalArgumentException("更新失败：路网 ID 不能为空");
        }
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("更新失败：路网名称不能为空");
        }
        int rows = 0;
        try {
            rows = graphsJdbcTemplate.update("UPDATE \"3d_road\".sys_road_network_by_xzq SET name = ? WHERE id = ?", newName.trim(),
                    networkId);
        } catch (Exception ignored) {}
        if (rows == 0) {
            rows = graphsJdbcTemplate.update("UPDATE upload_shp_road.sys_road_network SET name = ? WHERE id = ?", newName.trim(),
                    networkId);
        }
        if (rows == 0) {
            try {
                rows = graphsJdbcTemplate.update("UPDATE xzq_road.sys_road_network_by_xzq SET name = ? WHERE id = ?", newName.trim(),
                        networkId);
            } catch (Exception ignored) {}
        }
        if (rows == 0) {
            try {
                rows = graphsJdbcTemplate.update("UPDATE xzq_road.sys_road_network SET name = ? WHERE id = ?", newName.trim(),
                        networkId);
            } catch (Exception ignored) {}
        }
        return rows > 0;
    }

    public Map<String, Object> findNearestEdge(double lng, double lat) {
        return findNearestEdge(null, lng, lat);
    }

    /**
     * 在指定路网库中查找离经纬度最近的边弧段属性
     */
    public Map<String, Object> findNearestEdge(String networkId, double lng, double lat) {
        RoadNetworkConfig config = getNetworkConfig(networkId);
        if (config == null || config.getNodedTable() == null) {
            return null;
        }
        String roadTable = config.getNodedTable();
        String sql = "SELECT id, source, target FROM " + roadTable + " " +
                "ORDER BY geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326) LIMIT 1";
        List<Map<String, Object>> list = graphsJdbcTemplate.queryForList(sql, lng, lat);
        if (list != null && !list.isEmpty()) {
            Map<String, Object> map = list.get(0);
            map.put("table", roadTable);
            return map;
        }
        return null;
    }

    public Map<String, Object> computeRoute(double startLng, double startLat, double endLng, double endLat) {
        return computeRoute(null, startLng, startLat, endLng, endLat, true);
    }

    /**
     * 在指定路网库中执行精确点对点路径规划（默认有向）
     */
    public Map<String, Object> computeRoute(String networkId, double startLng, double startLat, double endLng,
            double endLat) {
        return computeRoute(networkId, startLng, startLat, endLng, endLat, true);
    }

    /**
     * 在指定路网库中执行精确点对点路径规划
     *
     * @param directed true-有向路径规划(考虑单行道规则)，false-无向路径规划
     */
    public Map<String, Object> computeRoute(String networkId, double startLng, double startLat, double endLng,
            double endLat, boolean directed) {
        RoadNetworkConfig config = getNetworkConfig(networkId);
        Map<String, Object> sEdgeMap = findNearestEdge(networkId, startLng, startLat);
        Map<String, Object> eEdgeMap = findNearestEdge(networkId, endLng, endLat);

        if (sEdgeMap == null || eEdgeMap == null) {
            return null;
        }

        String roadTable = (String) sEdgeMap.get("table");

        long sEdgeId = ((Number) sEdgeMap.get("id")).longValue();
        long sSrc = ((Number) sEdgeMap.get("source")).longValue();
        long sTgt = ((Number) sEdgeMap.get("target")).longValue();

        long eEdgeId = ((Number) eEdgeMap.get("id")).longValue();

        // 1. 同一条边上的起点终点 —— 精确判定单行道方向与裁剪子线段
        if (sEdgeId == eEdgeId) {
            try {
                String sqlSame = "WITH pts AS ( " +
                        "  SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS sp, " +
                        "         ST_SetSRID(ST_MakePoint(?, ?), 4326) AS ep " +
                        "), " +
                        "edge AS (SELECT geom, cost, reverse_cost FROM " + roadTable + " WHERE id = ?), " +
                        "fracs AS ( " +
                        "  SELECT ST_LineLocatePoint(e.geom, p.sp) AS fs, " +
                        "         ST_LineLocatePoint(e.geom, p.ep) AS fe, " +
                        "         e.cost, e.reverse_cost, e.geom " +
                        "  FROM edge e, pts p " +
                        "), " +
                        "valid_same AS ( " +
                        "  SELECT " +
                        "    CASE " +
                        "      WHEN f.fs <= f.fe AND (" + (!directed)
                        + " OR f.cost >= 0) THEN ST_LineSubstring(f.geom, f.fs, GREATEST(f.fs + 0.0001, f.fe)) " +
                        "      WHEN f.fs > f.fe AND (" + (!directed)
                        + " OR f.reverse_cost >= 0) THEN ST_Reverse(ST_LineSubstring(f.geom, f.fe, GREATEST(f.fe + 0.0001, f.fs))) "
                        +
                        "      ELSE NULL " +
                        "    END AS geom " +
                        "  FROM fracs f " +
                        ") " +
                        "SELECT ST_Length(v.geom::geography) AS total_distance, " +
                        "       json_build_object( " +
                        "           'type', 'FeatureCollection', " +
                        "           'features', json_build_array( " +
                        "               json_build_object( " +
                        "                   'type', 'Feature', " +
                        "                   'geometry', ST_AsGeoJSON(v.geom)::json, " +
                        "                   'properties', json_build_object('seq', 0) " +
                        "               ) " +
                        "           ) " +
                        "       )::text AS route_geojson " +
                        "FROM valid_same v WHERE v.geom IS NOT NULL";

                List<Map<String, Object>> rows = graphsJdbcTemplate.queryForList(sqlSame,
                        startLng, startLat, endLng, endLat, sEdgeId);
                if (rows != null && !rows.isEmpty()) {
                    Map<String, Object> row = rows.get(0);
                    row.put("startNode", sSrc);
                    row.put("endNode", sTgt);
                    return row;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 2. 不同弧段：精准去重起终点过冲多余残段，生成 100% 连续的 LineString 单一几何
        try {
            String pgrSelect;
            if (!directed) {
                pgrSelect = "SELECT id, source, target, " +
                        "ST_Length(geom::geography) AS cost, " +
                        "ST_Length(geom::geography) AS reverse_cost " +
                        "FROM " + roadTable;
            } else {
                pgrSelect = "SELECT id, source, target, " +
                        "COALESCE(cost, ST_Length(geom::geography)) AS cost, " +
                        "COALESCE(reverse_cost, ST_Length(geom::geography)) AS reverse_cost " +
                        "FROM " + roadTable;
            }

            String sqlFull = "WITH " +
                    "start_pt AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS pt), " +
                    "end_pt   AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS pt), " +

                    "s_edge AS ( " +
                    "  SELECT n.id, n.source, n.target, n.geom, " +
                    "         CASE WHEN n.cost >= 0 AND n.reverse_cost < 0 THEN 'F' WHEN n.cost < 0 AND n.reverse_cost >= 0 THEN 'T' ELSE 'B' END AS oneway, "
                    +
                    "         ST_Length(n.geom::geography) AS len, " +
                    "         ST_LineLocatePoint(n.geom, (SELECT pt FROM start_pt)) AS f " +
                    "  FROM " + roadTable + " n " +
                    "  WHERE n.id = ? " +
                    "), " +

                    "e_edge AS ( " +
                    "  SELECT n.id, n.source, n.target, n.geom, " +
                    "         CASE WHEN n.cost >= 0 AND n.reverse_cost < 0 THEN 'F' WHEN n.cost < 0 AND n.reverse_cost >= 0 THEN 'T' ELSE 'B' END AS oneway, "
                    +
                    "         ST_Length(n.geom::geography) AS len, " +
                    "         ST_LineLocatePoint(n.geom, (SELECT pt FROM end_pt)) AS f " +
                    "  FROM " + roadTable + " n " +
                    "  WHERE n.id = ? " +
                    "), " +

                    "combos AS ( " +
                    "  SELECT 1 AS cid, s.source AS sn, e.source AS en, (s.f * s.len) AS cost_s, (e.f * e.len) AS cost_e FROM s_edge s, e_edge e "
                    +
                    "  WHERE NOT " + directed + " OR (s.oneway <> 'F' AND e.oneway <> 'T') " +
                    "  UNION ALL " +
                    "  SELECT 2, s.source, e.target, (s.f * s.len), ((1-e.f) * e.len) FROM s_edge s, e_edge e " +
                    "  WHERE NOT " + directed + " OR (s.oneway <> 'F' AND e.oneway <> 'F') " +
                    "  UNION ALL " +
                    "  SELECT 3, s.target, e.source, ((1-s.f) * s.len), (e.f * e.len) FROM s_edge s, e_edge e " +
                    "  WHERE NOT " + directed + " OR (s.oneway <> 'T' AND e.oneway <> 'T') " +
                    "  UNION ALL " +
                    "  SELECT 4, s.target, e.target, ((1-s.f) * s.len), ((1-e.f) * e.len) FROM s_edge s, e_edge e " +
                    "  WHERE NOT " + directed + " OR (s.oneway <> 'T' AND e.oneway <> 'F') " +
                    "), " +

                    "pgr_select_cte AS ( " +
                    "  SELECT '" + pgrSelect.replace("'", "''") + "' AS query " +
                    "), " +

                    "best_combo AS ( " +
                    "  SELECT c.cid, c.sn, c.en, " +
                    "         (c.cost_s + COALESCE(SUM(ST_Length(e.geom::geography)), 0) + c.cost_e) AS total_dist " +
                    "  FROM combos c " +
                    "  JOIN pgr_select_cte ps ON true " +
                    "  LEFT JOIN pgr_dijkstra(ps.query, c.sn, c.en, " + directed + ") r ON true " +
                    "  LEFT JOIN " + roadTable + " e ON r.edge = e.id " +
                    "  GROUP BY c.cid, c.sn, c.en, c.cost_s, c.cost_e " +
                    "  HAVING c.sn = c.en OR COUNT(e.id) > 0 " +
                    "  ORDER BY total_dist ASC, (COALESCE(SUM(ST_Length(e.geom::geography)), 0)) ASC " +
                    "  LIMIT 1 " +
                    "), " +

                    "dijkstra_path AS ( " +
                    "  SELECT r.seq, r.node, r.edge, e.source AS e_source, e.target AS e_target, e.geom " +
                    "  FROM best_combo bc " +
                    "  JOIN pgr_select_cte ps ON true " +
                    "  JOIN pgr_dijkstra(ps.query, bc.sn, bc.en, " + directed + ") r ON true " +
                    "  JOIN " + roadTable + " e ON r.edge = e.id " +
                    "  ORDER BY r.seq " +
                    "), " +

                    "path_bounds AS ( " +
                    "  SELECT " +
                    "    (SELECT edge FROM dijkstra_path ORDER BY seq ASC LIMIT 1) AS first_edge, " +
                    "    (SELECT edge FROM dijkstra_path ORDER BY seq DESC LIMIT 1) AS last_edge " +
                    "), " +

                    "seg_start AS ( " +
                    "  SELECT " +
                    "    CASE " +
                    "      WHEN pb.first_edge = s.id THEN " +
                    "        CASE " +
                    "          WHEN dp.e_source = dp.node THEN ST_LineSubstring(s.geom, LEAST(s.f, 0.9999), 1.0) " +
                    "          ELSE ST_Reverse(ST_LineSubstring(s.geom, 0.0, GREATEST(0.0001, s.f))) " +
                    "        END " +
                    "      ELSE " +
                    "        CASE " +
                    "          WHEN bc.sn = s.source THEN ST_Reverse(ST_LineSubstring(s.geom, 0.0, GREATEST(0.0001, s.f))) "
                    +
                    "          ELSE ST_LineSubstring(s.geom, LEAST(s.f, 0.9999), 1.0) " +
                    "        END " +
                    "    END AS geom " +
                    "  FROM s_edge s, best_combo bc, path_bounds pb " +
                    "  LEFT JOIN dijkstra_path dp ON dp.seq = 1 " +
                    "), " +

                    "seg_end AS ( " +
                    "  SELECT " +
                    "    CASE " +
                    "      WHEN pb.last_edge = e.id THEN " +
                    "        CASE " +
                    "          WHEN dp.e_source = dp.node THEN ST_LineSubstring(e.geom, 0.0, GREATEST(0.0001, e.f)) " +
                    "          ELSE ST_Reverse(ST_LineSubstring(e.geom, LEAST(e.f, 0.9999), 1.0)) " +
                    "        END " +
                    "      ELSE " +
                    "        CASE " +
                    "          WHEN bc.en = e.source THEN ST_LineSubstring(e.geom, 0.0, GREATEST(0.0001, e.f)) " +
                    "          ELSE ST_Reverse(ST_LineSubstring(e.geom, LEAST(e.f, 0.9999), 1.0)) " +
                    "        END " +
                    "    END AS geom " +
                    "  FROM e_edge e, best_combo bc, path_bounds pb " +
                    "  LEFT JOIN (SELECT * FROM dijkstra_path ORDER BY seq DESC LIMIT 1) dp ON true " +
                    "), " +

                    "mid_edges AS ( " +
                    "  SELECT dp.seq, " +
                    "         CASE WHEN dp.node = dp.e_source THEN dp.geom ELSE ST_Reverse(dp.geom) END AS geom " +
                    "  FROM dijkstra_path dp, path_bounds pb, s_edge s, e_edge e " +
                    "  WHERE NOT (pb.first_edge = s.id AND dp.seq = 1) " +
                    "    AND NOT (pb.last_edge = e.id AND dp.seq = (SELECT MAX(seq) FROM dijkstra_path)) " +
                    "), " +

                    "all_segs AS ( " +
                    "  SELECT 0 AS ord, geom FROM seg_start " +
                    "  UNION ALL " +
                    "  SELECT seq AS ord, geom FROM mid_edges " +
                    "  UNION ALL " +
                    "  SELECT 999999 AS ord, geom FROM seg_end " +
                    "), " +

                    "pts_seq AS ( " +
                    "  SELECT (ST_DumpPoints(geom)).geom AS pt " +
                    "  FROM all_segs " +
                    "  WHERE geom IS NOT NULL " +
                    "  ORDER BY ord " +
                    ") " +

                    "SELECT " +
                    "  (SELECT total_dist FROM best_combo) AS total_distance, " +
                    "  (SELECT sn FROM best_combo) AS startNode, " +
                    "  (SELECT en FROM best_combo) AS endNode, " +
                    "  json_build_object( " +
                    "      'type', 'FeatureCollection', " +
                    "      'features', json_build_array( " +
                    "          json_build_object( " +
                    "              'type', 'Feature', " +
                    "              'geometry', ST_AsGeoJSON(ST_MakeLine(pt))::json, " +
                    "              'properties', json_build_object('seq', 0) " +
                    "          ) " +
                    "      ) " +
                    "  )::text AS route_geojson " +
                    "FROM pts_seq " +
                    "GROUP BY 1, 2, 3 " +
                    "LIMIT 1";

            List<Map<String, Object>> rows = graphsJdbcTemplate.queryForList(sqlFull,
                    startLng, startLat,
                    endLng, endLat,
                    sEdgeId, eEdgeId);

            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                if (row.get("startnode") != null) {
                    row.put("startNode", ((Number) row.get("startnode")).longValue());
                }
                if (row.get("endnode") != null) {
                    row.put("endNode", ((Number) row.get("endnode")).longValue());
                }
                return row;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public Object getRoadRange(Double minLng, Double minLat, Double maxLng, Double maxLat) {
        return getRoadRange(null, minLng, minLat, maxLng, maxLat);
    }

    /**
     * 从 graphs 库中查询指定路网的 GeoJSON FeatureCollection 特征数据（用于地图渲染路网背景）
     */
    public Object getRoadRange(String networkId, Double minLng, Double minLat, Double maxLng, Double maxLat) {
        RoadNetworkConfig config = getNetworkConfig(networkId);
        if (config == null || config.getRoadTable() == null) {
            return null;
        }
        String roadTable = config.getRoadTable();

        try {
            String sql;
            Object[] args;
            if (minLng != null && minLat != null && maxLng != null && maxLat != null) {
                sql = "SELECT json_build_object(" +
                        "    'type', 'FeatureCollection', " +
                        "    'features', COALESCE(json_agg(" +
                        "        json_build_object(" +
                        "            'type', 'Feature', " +
                        "            'geometry', ST_AsGeoJSON(t.geom)::json, " +
                        "            'properties', json_build_object(" +
                        "                'gid', COALESCE(to_jsonb(t)->>'gid', to_jsonb(t)->>'id'), " +
                        "                'id', COALESCE(to_jsonb(t)->>'gid', to_jsonb(t)->>'id'), " +
                        "                'name', to_jsonb(t)->>'name'" +
                        "            ) " +
                        "        )" +
                        "    ), '[]'::json) " +
                        ")::text AS geojson " +
                        "FROM " + roadTable + " t " +
                        "WHERE ST_Intersects(t.geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))";
                args = new Object[] { minLng, minLat, maxLng, maxLat };
            } else {
                sql = "SELECT json_build_object(" +
                        "    'type', 'FeatureCollection', " +
                        "    'features', COALESCE(json_agg(" +
                        "        json_build_object(" +
                        "            'type', 'Feature', " +
                        "            'geometry', ST_AsGeoJSON(sub.geom)::json, " +
                        "            'properties', json_build_object(" +
                        "                'gid', COALESCE(to_jsonb(sub)->>'gid', to_jsonb(sub)->>'id'), " +
                        "                'id', COALESCE(to_jsonb(sub)->>'gid', to_jsonb(sub)->>'id'), " +
                        "                'name', to_jsonb(sub)->>'name'" +
                        "            ) " +
                        "        )" +
                        "    ), '[]'::json) " +
                        ")::text AS geojson " +
                        "FROM (SELECT * FROM " + roadTable + " LIMIT 3000) sub";
                args = new Object[0];
            }

            List<String> result = graphsJdbcTemplate.query(sql, args, (rs, rowNum) -> rs.getString("geojson"));
            if (result != null && !result.isEmpty() && result.get(0) != null) {
                return objectMapper.readTree(result.get(0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 根据前端计算出的路线坐标线段，从 PostGIS 中匹配查询沿途经过的道路名称与各分段信息
     *
     * @param networkId   路网标识 (可选)
     * @param coordinates 路线折线经纬度坐标点集 [[lng, lat], ...]
     * @return 包含 roadNames、sections、segments 以及总里程统计的 Map 结果
     */
    public Map<String, Object> getRoadNamesAlongRoute(String networkId, List<List<Double>> coordinates) {
        Map<String, Object> result = new HashMap<>();
        if (coordinates == null || coordinates.size() < 2) {
            result.put("totalDistance", 0.0);
            result.put("roadNames", Collections.emptyList());
            result.put("sections", Collections.emptyList());
            result.put("segments", Collections.emptyList());
            return result;
        }

        // 1. 构建 PostGIS LineString WKT 字符串
        StringBuilder sbWkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i < coordinates.size(); i++) {
            List<Double> pt = coordinates.get(i);
            if (pt == null || pt.size() < 2) continue;
            if (i > 0) sbWkt.append(", ");
            sbWkt.append(pt.get(0)).append(" ").append(pt.get(1));
        }
        sbWkt.append(")");
        String lineWkt = sbWkt.toString();

        // 2. 利用 PostGIS ST_DumpPoints 与 LATERAL 邻近匹配 osm.sc_road (优先带路名者)
        String sql = "WITH line AS ( " +
                "  SELECT ST_SetSRID(ST_GeomFromText(?, 4326), 4326) AS geom " +
                "), " +
                "points AS ( " +
                "  SELECT (ST_DumpPoints(line.geom)).path[1] AS pt_idx, " +
                "         (ST_DumpPoints(line.geom)).geom AS pt_geom " +
                "  FROM line " +
                "), " +
                "segs AS ( " +
                "  SELECT p1.pt_idx AS seg_idx, " +
                "         ST_MakeLine(p1.pt_geom, p2.pt_geom) AS seg_geom, " +
                "         ST_LineInterpolatePoint(ST_MakeLine(p1.pt_geom, p2.pt_geom), 0.5) AS mid_geom, " +
                "         ST_Length(ST_MakeLine(p1.pt_geom, p2.pt_geom)::geography) AS seg_len " +
                "  FROM points p1 " +
                "  JOIN points p2 ON p1.pt_idx + 1 = p2.pt_idx " +
                ") " +
                "SELECT s.seg_idx, " +
                "       ROUND(s.seg_len::numeric, 1) AS seg_len_m, " +
                "       COALESCE(NULLIF(r.name, ''), '未名道路') AS road_name, " +
                "       COALESCE(r.ref, '') AS ref, " +
                "       COALESCE(r.fclass, '') AS fclass, " +
                "       ST_X(s.mid_geom) AS mid_lng, " +
                "       ST_Y(s.mid_geom) AS mid_lat " +
                "FROM segs s " +
                "LEFT JOIN LATERAL ( " +
                "  SELECT name, ref, fclass, geom " +
                "  FROM osm.sc_road " +
                "  WHERE ST_DWithin(geom, s.mid_geom, 0.0005) " +
                "  ORDER BY (CASE WHEN name IS NOT NULL AND name != '' THEN 0 ELSE 1 END), geom <-> s.mid_geom " +
                "  LIMIT 1 " +
                ") r ON true " +
                "ORDER BY s.seg_idx;";

        List<Map<String, Object>> segRows = Collections.emptyList();
        try {
            segRows = primaryJdbcTemplate.queryForList(sql, lineWkt);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. 处理各分段数据并聚合为道路区间 (Sections)
        List<Map<String, Object>> segments = new ArrayList<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        List<String> roadNamesList = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        double totalDist = 0.0;
        Map<String, Object> currentSection = null;

        for (int i = 0; i < segRows.size(); i++) {
            Map<String, Object> row = segRows.get(i);
            int segIdx = ((Number) row.get("seg_idx")).intValue(); // 1-based
            double segLen = ((Number) row.get("seg_len_m")).doubleValue();
            String roadName = (String) row.get("road_name");
            String ref = (String) row.get("ref");
            String fclass = (String) row.get("fclass");

            totalDist += segLen;

            List<Double> startCoord = (segIdx - 1 < coordinates.size()) ? coordinates.get(segIdx - 1) : null;
            List<Double> endCoord = (segIdx < coordinates.size()) ? coordinates.get(segIdx) : null;

            Map<String, Object> segItem = new HashMap<>();
            segItem.put("segIndex", segIdx);
            segItem.put("roadName", roadName);
            segItem.put("length", segLen);
            segItem.put("ref", ref);
            segItem.put("fclass", fclass);
            segItem.put("startCoord", startCoord);
            segItem.put("endCoord", endCoord);
            segments.add(segItem);

            if (roadName != null && !roadName.equals("未名道路") && seenNames.add(roadName)) {
                roadNamesList.add(roadName);
            }

            // 区间聚合：相同路名合并为一个 section
            if (currentSection == null) {
                currentSection = new HashMap<>();
                currentSection.put("roadName", roadName);
                currentSection.put("ref", ref);
                currentSection.put("fclass", fclass);
                currentSection.put("length", segLen);
                currentSection.put("startIndex", segIdx - 1);
                currentSection.put("endIndex", segIdx);
                currentSection.put("startCoord", startCoord);
                currentSection.put("endCoord", endCoord);
            } else {
                String curRoad = (String) currentSection.get("roadName");
                if (Objects.equals(curRoad, roadName)) {
                    double prevLen = ((Number) currentSection.get("length")).doubleValue();
                    currentSection.put("length", Math.round((prevLen + segLen) * 10.0) / 10.0);
                    currentSection.put("endIndex", segIdx);
                    currentSection.put("endCoord", endCoord);
                } else {
                    sections.add(currentSection);
                    currentSection = new HashMap<>();
                    currentSection.put("roadName", roadName);
                    currentSection.put("ref", ref);
                    currentSection.put("fclass", fclass);
                    currentSection.put("length", segLen);
                    currentSection.put("startIndex", segIdx - 1);
                    currentSection.put("endIndex", segIdx);
                    currentSection.put("startCoord", startCoord);
                    currentSection.put("endCoord", endCoord);
                }
            }
        }

        if (currentSection != null) {
            sections.add(currentSection);
        }

        result.put("totalDistance", Math.round(totalDist * 10.0) / 10.0);
        result.put("roadNames", roadNamesList);
        result.put("sections", sections);
        result.put("segments", segments);

        return result;
    }

}
