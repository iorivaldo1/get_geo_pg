package com.qskj.get_geo_pg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class XzqRoadBuildService {

    private final Map<String, List<String>> tableColsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> tableTotalCache = new ConcurrentHashMap<>();

    @Autowired
    @Qualifier("jdbcTemplate")
    private JdbcTemplate primaryJdbcTemplate; // mdb1

    @Autowired
    @Qualifier("graphsJdbcTemplate")
    private JdbcTemplate graphsJdbcTemplate; // graphs

    /**
     * 1. 动态扫描 sc_xzq 模式下的所有行政区划矢量表并推断行政级别
     */
    public List<Map<String, Object>> getAvailableXzqLevels() {
        List<Map<String, Object>> levels = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        try {
            // 仅查询包含 geom/geometry 空间列的真实矢量图层表，排除配置表与非空间表
            List<Map<String, Object>> tables = primaryJdbcTemplate.queryForList(
                    "SELECT DISTINCT table_name " +
                            "FROM information_schema.columns " +
                            "WHERE table_schema = 'sc_xzq' AND column_name IN ('geom', 'geometry', 'wkb_geometry') " +
                            "ORDER BY table_name");

            for (Map<String, Object> row : tables) {
                String tbl = (String) row.get("table_name");
                Map<String, Object> levelInfo = resolveTableInfo(tbl);
                if (levelInfo != null) {
                    String key = (String) levelInfo.get("key");
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key);
                        levels.add(levelInfo);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (levels.isEmpty()) {
            Map<String, Object> cityMap = new HashMap<>();
            cityMap.put("key", "city");
            cityMap.put("name", "市级 (City)");
            cityMap.put("tableName", "sc_xzq.xzq_sc_city");

            Map<String, Object> countyMap = new HashMap<>();
            countyMap.put("key", "county");
            countyMap.put("name", "区县级 (County)");
            countyMap.put("tableName", "sc_xzq.xzq_sc_county");

            Map<String, Object> townMap = new HashMap<>();
            townMap.put("key", "town");
            townMap.put("name", "乡镇级 (Town)");
            townMap.put("tableName", "sc_xzq.xzq_sc_town");

            Map<String, Object> villMap = new HashMap<>();
            villMap.put("key", "village");
            villMap.put("name", "街道/村级 (Village)");
            villMap.put("tableName", "sc_xzq.xzq_sc_village");

            levels.add(cityMap);
            levels.add(countyMap);
            levels.add(townMap);
            levels.add(villMap);
        } else {
            // 确保行政级别自上而下规范排序：市级 -> 区县级 -> 乡镇级 -> 街道/村级
            List<String> sortOrder = Arrays.asList("city", "county", "town", "village");
            levels.sort(Comparator.comparingInt(item -> {
                String k = (String) item.get("key");
                int idx = sortOrder.indexOf(k);
                return idx >= 0 ? idx : 99;
            }));
        }

        return levels;
    }

    private Map<String, Object> resolveTableInfo(String tableName) {
        if (tableName == null || tableName.trim().isEmpty())
            return null;

        String fullTable = tableName.contains(".") ? tableName : "sc_xzq." + tableName;
        String cleanTable = tableName.contains(".") ? tableName.split("\\.")[1] : tableName;

        Map<String, Object> info = new HashMap<>();
        info.put("tableName", fullTable);

        List<String> cols = getTableColumns(fullTable);

        if (cleanTable.contains("city")) {
            info.put("key", "city");
            info.put("name", "市级 (City)");
        } else if (cleanTable.contains("county") || cleanTable.contains("qx")) {
            info.put("key", "county");
            info.put("name", "区县级 (County)");
        } else if (cleanTable.contains("town")) {
            info.put("key", "town");
            info.put("name", "乡镇级 (Town)");
        } else if (cleanTable.contains("vill") || cleanTable.contains("village")) {
            info.put("key", "village");
            info.put("name", "街道/村级 (Village)");
        } else if (containsCol(cols, "村级名", "村级代码", "村级名称", "村名")) {
            info.put("key", "village");
            info.put("name", "街道/村级 (Village)");
        } else if (containsCol(cols, "乡镇级", "乡镇名称")) {
            info.put("key", "town");
            info.put("name", "乡镇级 (Town)");
        } else if (containsCol(cols, "区县级", "区县名", "县级")) {
            info.put("key", "county");
            info.put("name", "区县级 (County)");
        } else if (containsCol(cols, "市级", "市名")) {
            info.put("key", "city");
            info.put("name", "市级 (City)");
        } else {
            info.put("key", cleanTable);
            info.put("name", cleanTable + " 矢量表");
        }

        return info;
    }

    private boolean containsCol(List<String> columns, String... candidates) {
        for (String candidate : candidates) {
            for (String col : columns) {
                if (candidate.equalsIgnoreCase(col))
                    return true;
            }
        }
        return false;
    }

    private List<String> getTableColumns(String fullTableName) {
        if (fullTableName != null && tableColsCache.containsKey(fullTableName)) {
            return tableColsCache.get(fullTableName);
        }
        try {
            String[] parts = fullTableName.split("\\.");
            String schema = parts.length > 1 ? parts[0] : "public";
            String table = parts.length > 1 ? parts[1] : parts[0];

            List<Map<String, Object>> rows = primaryJdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                    schema, table);
            List<String> cols = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                cols.add((String) r.get("column_name"));
            }
            if (fullTableName != null && !cols.isEmpty()) {
                tableColsCache.put(fullTableName, cols);
            }
            return cols;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String resolveTableName(String levelOrTable) {
        if (levelOrTable == null)
            return "sc_xzq.xzq_sc_town";

        if (levelOrTable.contains(".")) {
            return levelOrTable;
        }

        if ("city".equalsIgnoreCase(levelOrTable)) {
            return "sc_xzq.xzq_sc_city";
        }
        if ("town".equalsIgnoreCase(levelOrTable)) {
            return "sc_xzq.xzq_sc_town";
        }
        if ("village".equalsIgnoreCase(levelOrTable)) {
            return "sc_xzq.xzq_sc_village";
        }
        if ("county".equalsIgnoreCase(levelOrTable)) {
            return "sc_xzq.xzq_sc_county";
        }

        List<Map<String, Object>> levels = getAvailableXzqLevels();
        for (Map<String, Object> lvl : levels) {
            if (levelOrTable.equalsIgnoreCase((String) lvl.get("key")) ||
                    levelOrTable.equalsIgnoreCase((String) lvl.get("name"))) {
                return (String) lvl.get("tableName");
            }
        }

        if (levelOrTable.startsWith("xzq_")) {
            return "sc_xzq." + levelOrTable;
        }

        return "sc_xzq.xzq_sc_town";
    }

    /**
     * 2. 动态获取行政区划名称与ID列表 (轻量快速，不加载 geom)
     */
    public Map<String, Object> getXzqListInfo(String level, int page, int pageSize, String keyword) {
        String tableName = resolveTableName(level);
        List<String> fieldsList = new ArrayList<>();
        String idCol = null;
        String nameCol = null;

        // 1. 查询 sc_xzq.xzq_field_list 配置表
        try {
            List<Map<String, Object>> cfgList = primaryJdbcTemplate.queryForList(
                    "SELECT fields, id_column, name_column FROM sc_xzq.xzq_field_list WHERE level = ?", level);
            if (cfgList != null && !cfgList.isEmpty()) {
                Map<String, Object> cfg = cfgList.get(0);
                String fStr = (String) cfg.get("fields");
                if (fStr != null && !fStr.trim().isEmpty()) {
                    for (String f : fStr.split(",")) {
                        if (!f.trim().isEmpty())
                            fieldsList.add(f.trim());
                    }
                }
                idCol = (String) cfg.get("id_column");
                nameCol = (String) cfg.get("name_column");
            }
        } catch (Exception e) {
            // Ignore if table access error
        }

        if (idCol == null) {
            idCol = findColumn(tableName, Arrays.asList("村级码", "乡镇码", "区县码", "市代码", "gid", "id"));
            if (idCol == null)
                idCol = "gid";
        }
        if (nameCol == null) {
            nameCol = findColumn(tableName, Arrays.asList("村级名", "乡镇级", "区县级", "市级", "name", "mc"));
            if (nameCol == null)
                nameCol = idCol;
        }

        if (fieldsList.isEmpty()) {
            if ("city".equalsIgnoreCase(level)) {
                fieldsList = Arrays.asList("市级");
            } else if ("county".equalsIgnoreCase(level)) {
                fieldsList = Arrays.asList("市级", "区县级");
            } else if ("town".equalsIgnoreCase(level)) {
                fieldsList = Arrays.asList("市级", "区县级", "乡镇级");
            } else if ("village".equalsIgnoreCase(level)) {
                fieldsList = Arrays.asList("市级", "区县级", "乡镇级", "村级名");
            } else {
                fieldsList = Arrays.asList(nameCol);
            }
        }

        List<String> validTableCols = getTableColumns(tableName);
        List<String> selectFields = new ArrayList<>();
        for (String f : fieldsList) {
            String match = findColumn(tableName, Collections.singletonList(f));
            if (match != null && !selectFields.contains(match)) {
                selectFields.add(match);
            }
        }
        if (selectFields.isEmpty()) {
            selectFields.add(nameCol);
        }

        StringBuilder whereSql = new StringBuilder(" WHERE geom IS NOT NULL ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = "%" + keyword.trim() + "%";
            List<String> orClauses = new ArrayList<>();
            orClauses.add(quoteCol(idCol) + "::text ILIKE ?");
            params.add(kw);
            orClauses.add(quoteCol(nameCol) + "::text ILIKE ?");
            params.add(kw);
            for (String f : selectFields) {
                if (!f.equals(idCol) && !f.equals(nameCol)) {
                    orClauses.add(quoteCol(f) + "::text ILIKE ?");
                    params.add(kw);
                }
            }
            whereSql.append(" AND (").append(String.join(" OR ", orClauses)).append(") ");
        }

        Integer total = 0;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String countSql = "SELECT COUNT(1) FROM " + tableName + whereSql.toString();
            total = primaryJdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());
        } else {
            if (tableTotalCache.containsKey(tableName)) {
                total = tableTotalCache.get(tableName);
            } else {
                try {
                    total = primaryJdbcTemplate.queryForObject("SELECT count(1) FROM " + tableName, Integer.class);
                } catch (Exception e) {
                    total = 1000;
                }
                if (total != null) tableTotalCache.put(tableName, total);
            }
        }
        if (total == null) total = 0;

        StringBuilder selectSql = new StringBuilder();
        selectSql.append("SELECT ").append(quoteCol(idCol)).append(" AS id, ")
                .append(quoteCol(nameCol)).append("::text AS name ");
        for (String col : selectFields) {
            selectSql.append(", ").append(quoteCol(col)).append("::text AS ").append(quoteCol(col));
        }
        selectSql.append(" FROM ").append(tableName).append(whereSql).append(" ORDER BY ")
                .append(quoteCol(idCol)).append(" ASC");

        if (pageSize > 0) {
            int offset = Math.max(0, (page - 1) * pageSize);
            selectSql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);
        }

        List<Map<String, Object>> rawList = primaryJdbcTemplate.queryForList(selectSql.toString(), params.toArray());
        List<Map<String, Object>> resultList = new ArrayList<>();

        for (Map<String, Object> row : rawList) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", row.get("id"));

            StringBuilder fullNameSb = new StringBuilder();
            Map<String, Object> fieldValues = new LinkedHashMap<>();
            for (String f : selectFields) {
                Object val = row.get(f);
                String valStr = val != null ? val.toString().trim() : "";
                fieldValues.put(f, valStr);
                if (!valStr.isEmpty()) {
                    fullNameSb.append(valStr);
                }
            }
            String fullName = fullNameSb.length() > 0 ? fullNameSb.toString()
                    : (row.get("name") != null && !row.get("name").toString().trim().isEmpty()
                            ? row.get("name").toString().trim()
                            : "要素_" + row.get("id"));

            item.put("name", fullName);
            item.put("fullName", fullName);
            item.put("fields", fieldValues);
            resultList.add(item);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("level", level);
        res.put("fields", selectFields);
        res.put("idColumn", idCol);
        res.put("nameColumn", nameCol);
        res.put("total", total);
        res.put("page", page);
        res.put("pageSize", pageSize);
        res.put("hasMore", (page * pageSize) < total);
        res.put("list", resultList);
        return res;
    }

    public Map<String, Object> getXzqListInfo(String level) {
        return getXzqListInfo(level, 1, 100, null);
    }

    public List<Map<String, Object>> getXzqList(String level) {
        Map<String, Object> info = getXzqListInfo(level, 1, 500, null);
        if (info != null && info.get("list") != null) {
            return (List<Map<String, Object>>) info.get("list");
        }
        return Collections.emptyList();
    }

    /**
     * 3. 点击列表要素时，按需读取单个要素的包围盒 (bbox) 与 GeoJSON (geom)
     */
    public Map<String, Object> getXzqDetail(String level, String featureId) {
        if (featureId != null && featureId.endsWith("_3d")) {
            featureId = featureId.substring(0, featureId.length() - 3);
        }
        String tableName = resolveTableName(level);

        String idCol = null;
        String nameCol = null;
        try {
            List<Map<String, Object>> cfgList = primaryJdbcTemplate.queryForList(
                    "SELECT id_column, name_column FROM sc_xzq.xzq_field_list WHERE level = ?", level);
            if (cfgList != null && !cfgList.isEmpty()) {
                idCol = (String) cfgList.get(0).get("id_column");
                nameCol = (String) cfgList.get(0).get("name_column");
            }
        } catch (Exception ignored) {
        }

        if (idCol == null) {
            List<String> idCandidates = Arrays.asList(
                    "村级码", "村级代码", "乡镇码", "乡镇代码", "区县码", "市代码", "gid", "id", "code");
            idCol = findColumn(tableName, idCandidates);
        }
        if (nameCol == null) {
            List<String> nameCandidates = Arrays.asList(
                    "村级名", "村级名称", "村名", "乡镇级", "乡镇名称", "乡镇名", "区县级", "区县名", "市级", "市名", "town_name", "vill_name", "village_name",
                    "name", "mc");
            nameCol = findColumn(tableName, nameCandidates);
        }

        if (idCol == null)
            idCol = "gid";
        if (nameCol == null)
            nameCol = "gid";

        List<String> hierarchyCandidates = Arrays.asList("市级", "市名", "区县级", "区县名", "乡镇级", "乡镇名", "村级名", "村名", "name", "mc");
        List<String> selectFields = new ArrayList<>();
        for (String f : hierarchyCandidates) {
            String match = findColumn(tableName, Collections.singletonList(f));
            if (match != null && !selectFields.contains(match)) {
                selectFields.add(match);
            }
        }
        if (selectFields.isEmpty()) {
            selectFields.add(nameCol);
        }

        StringBuilder extraCols = new StringBuilder();
        for (String col : selectFields) {
            extraCols.append(", ").append(quoteCol(col)).append("::text AS ").append(quoteCol(col));
        }

        String sql = String.format(
                "SELECT %s AS id, %s::text AS name%s, " +
                        "ST_XMin(geom) AS min_x, ST_XMax(geom) AS max_x, " +
                        "ST_YMin(geom) AS min_y, ST_YMax(geom) AS max_y, " +
                        "ST_AsGeoJSON(geom) AS geojson " +
                        "FROM %s " +
                        "WHERE %s::text = ?",
                quoteCol(idCol), quoteCol(nameCol), extraCols.toString(), tableName, quoteCol(idCol));

        List<Map<String, Object>> rows = primaryJdbcTemplate.queryForList(sql, featureId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        Map<String, Object> item = new HashMap<>();
        item.put("id", row.get("id"));

        StringBuilder fullNameSb = new StringBuilder();
        Map<String, Object> fieldValues = new LinkedHashMap<>();
        for (String f : selectFields) {
            Object val = row.get(f);
            String valStr = val != null ? val.toString().trim() : "";
            fieldValues.put(f, valStr);
            if (!valStr.isEmpty()) {
                fullNameSb.append(valStr);
            }
        }
        String fullName = fullNameSb.length() > 0 ? fullNameSb.toString() : (row.get("name") != null ? row.get("name").toString().trim() : featureId.toString());
        item.put("name", fullName);
        item.put("fullName", fullName);
        item.put("fields", fieldValues);

        Double minX = row.get("min_x") != null ? ((Number) row.get("min_x")).doubleValue() : null;
        Double maxX = row.get("max_x") != null ? ((Number) row.get("max_x")).doubleValue() : null;
        Double minY = row.get("min_y") != null ? ((Number) row.get("min_y")).doubleValue() : null;
        Double maxY = row.get("max_y") != null ? ((Number) row.get("max_y")).doubleValue() : null;

        if (minX != null && maxX != null && minY != null && maxY != null) {
            item.put("bbox", Arrays.asList(minX, minY, maxX, maxY));
        } else {
            item.put("bbox", null);
        }

        item.put("geojson", row.get("geojson"));
        return item;
    }

    private void ensureRoadSchemaAndTable() {
        try {
            graphsJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS xzq_road;");
            graphsJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS xzq_road.sys_road_network_by_xzq (" +
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
            graphsJdbcTemplate.execute("ALTER TABLE IF EXISTS xzq_road.sys_road_network_by_xzq ADD COLUMN IF NOT EXISTS build_time TIMESTAMP DEFAULT NOW();");
            graphsJdbcTemplate.execute("ALTER TABLE IF EXISTS xzq_road.sys_road_network ADD COLUMN IF NOT EXISTS build_time TIMESTAMP DEFAULT NOW();");
        } catch (Exception e) {
            try {
                graphsJdbcTemplate.execute("ROLLBACK;");
            } catch (Exception ignored) {
            }
        }
    }

    private void ensure3dRoadSchemaAndTable() {
        try {
            graphsJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS \"3d_road\";");
            graphsJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS \"3d_road\".sys_road_network_by_xzq (" +
                    "id VARCHAR(64) PRIMARY KEY, " +
                    "name VARCHAR(128) NOT NULL, " +
                    "road_table VARCHAR(128) NOT NULL, " +
                    "noded_table VARCHAR(128) NOT NULL, " +
                    "center_lng DOUBLE PRECISION, " +
                    "center_lat DOUBLE PRECISION, " +
                    "default_zoom INT DEFAULT 15, " +
                    "sort_order INT DEFAULT 10, " +
                    "status INT DEFAULT 1, " +
                    "topo_mode VARCHAR(32) DEFAULT 'layered', " +
                    "has_bridge BOOLEAN DEFAULT true, " +
                    "has_tunnel BOOLEAN DEFAULT true" +
                    ");");
            graphsJdbcTemplate.execute("ALTER TABLE IF EXISTS \"3d_road\".sys_road_network_by_xzq ADD COLUMN IF NOT EXISTS build_time TIMESTAMP DEFAULT NOW();");
        } catch (Exception e) {
            try {
                graphsJdbcTemplate.execute("ROLLBACK;");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取数据库中通过行政区划配置的所有有效路网 (XZQ 模式 + 3D 模式)
     */
    public List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> getAllNetworks() {
        return getAllNetworks("all");
    }

    /**
     * 按模式获取行政区划路网配置列表
     * @param mode 可选值: "2d" (仅行政区划2D), "3d" (仅3D立体分层), "all" (全部)
     */
    public List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> getAllNetworks(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            mode = "all";
        }
        String m = mode.trim().toLowerCase();
        boolean need3d = m.contains("3d") || m.equals("all");
        boolean need2d = m.contains("2d") || m.contains("xzq") || m.equals("all");

        List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> results = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // 1. 查询 3d_road.sys_road_network_by_xzq
        if (need3d) {
            try {
                ensure3dRoadSchemaAndTable();
                String sql3d = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                        + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                        + "FROM \"3d_road\".sys_road_network_by_xzq WHERE status = 1 ORDER BY sort_order ASC, id ASC";
                List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> d3List = graphsJdbcTemplate.query(sql3d, (rs, rowNum) -> {
                    com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = new com.qskj.get_geo_pg.pojo.RoadNetworkConfig();
                    config.setId(rs.getString("id"));
                    config.setName(rs.getString("name"));
                    config.setRoadTable(rs.getString("road_table"));
                    config.setNodedTable(rs.getString("noded_table"));
                    config.setCenterLng(rs.getDouble("center_lng"));
                    config.setCenterLat(rs.getDouble("center_lat"));
                    config.setDefaultZoom(rs.getInt("default_zoom"));
                    config.setSortOrder(rs.getInt("sort_order"));
                    config.setStatus(rs.getInt("status"));
                    config.setBuildTime(rs.getString("build_time"));
                    return config;
                });
                for (com.qskj.get_geo_pg.pojo.RoadNetworkConfig c : d3List) {
                    if (seenIds.add(c.getId())) {
                        results.add(c);
                    }
                }
            } catch (Exception ignored) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignored2) {
                }
            }
        }

        // 2. 查询 xzq_road.sys_road_network_by_xzq
        if (need2d) {
            try {
                ensureRoadSchemaAndTable();
                String sql = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                        + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                        + "FROM xzq_road.sys_road_network_by_xzq WHERE status = 1 ORDER BY sort_order ASC, id ASC";
                try {
                    List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> xzqList = graphsJdbcTemplate.query(sql, (rs, rowNum) -> {
                        com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = new com.qskj.get_geo_pg.pojo.RoadNetworkConfig();
                        config.setId(rs.getString("id"));
                        config.setName(rs.getString("name"));
                        config.setRoadTable(rs.getString("road_table"));
                        config.setNodedTable(rs.getString("noded_table"));
                        config.setCenterLng(rs.getDouble("center_lng"));
                        config.setCenterLat(rs.getDouble("center_lat"));
                        config.setDefaultZoom(rs.getInt("default_zoom"));
                        config.setSortOrder(rs.getInt("sort_order"));
                        config.setStatus(rs.getInt("status"));
                        config.setBuildTime(rs.getString("build_time"));
                        return config;
                    });
                    for (com.qskj.get_geo_pg.pojo.RoadNetworkConfig c : xzqList) {
                        if (seenIds.add(c.getId())) {
                            results.add(c);
                        }
                    }
                } catch (Exception e) {
                    // 兼容表名可能为 xzq_road.sys_road_network 的情况
                    String sqlFallback = "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                            + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                            + "FROM xzq_road.sys_road_network WHERE status = 1 ORDER BY sort_order ASC, id ASC";
                    List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> fallbackList = graphsJdbcTemplate.query(sqlFallback, (rs, rowNum) -> {
                        com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = new com.qskj.get_geo_pg.pojo.RoadNetworkConfig();
                        config.setId(rs.getString("id"));
                        config.setName(rs.getString("name"));
                        config.setRoadTable(rs.getString("road_table"));
                        config.setNodedTable(rs.getString("noded_table"));
                        config.setCenterLng(rs.getDouble("center_lng"));
                        config.setCenterLat(rs.getDouble("center_lat"));
                        config.setDefaultZoom(rs.getInt("default_zoom"));
                        config.setSortOrder(rs.getInt("sort_order"));
                        config.setStatus(rs.getInt("status"));
                        config.setBuildTime(rs.getString("build_time"));
                        return config;
                    });
                    for (com.qskj.get_geo_pg.pojo.RoadNetworkConfig c : fallbackList) {
                        if (seenIds.add(c.getId())) {
                            results.add(c);
                        }
                    }
                }
            } catch (Exception e) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignored) {
                }
            }
        }

        return results;
    }

    /**
     * 根据 networkId 检索匹配的行政区划路网配置
     */
    public com.qskj.get_geo_pg.pojo.RoadNetworkConfig getNetworkConfig(String networkId) {
        List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> list = getAllNetworks();
        if (networkId != null && !networkId.trim().isEmpty()) {
            for (com.qskj.get_geo_pg.pojo.RoadNetworkConfig cfg : list) {
                if (networkId.equalsIgnoreCase(cfg.getId())) {
                    return cfg;
                }
            }

            // 1. 尝试在 "3d_road".sys_road_network_by_xzq 中精确匹配
            try {
                List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> d3List = graphsJdbcTemplate.query(
                        "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                                + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                                + "FROM \"3d_road\".sys_road_network_by_xzq WHERE id = ?",
                        (rs, rowNum) -> {
                            com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = new com.qskj.get_geo_pg.pojo.RoadNetworkConfig();
                            config.setId(rs.getString("id"));
                            config.setName(rs.getString("name"));
                            config.setRoadTable(rs.getString("road_table"));
                            config.setNodedTable(rs.getString("noded_table"));
                            config.setCenterLng(rs.getDouble("center_lng"));
                            config.setCenterLat(rs.getDouble("center_lat"));
                            config.setDefaultZoom(rs.getInt("default_zoom"));
                            config.setSortOrder(rs.getInt("sort_order"));
                            config.setStatus(rs.getInt("status"));
                            config.setBuildTime(rs.getString("build_time"));
                            return config;
                        }, networkId);
                if (d3List != null && !d3List.isEmpty()) {
                    return d3List.get(0);
                }
            } catch (Exception ignored) {
            }

            // 2. 尝试在 xzq_road.sys_road_network_by_xzq 中检索
            try {
                List<com.qskj.get_geo_pg.pojo.RoadNetworkConfig> xzqList = graphsJdbcTemplate.query(
                        "SELECT id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, "
                                + "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time "
                                + "FROM xzq_road.sys_road_network_by_xzq WHERE id = ?",
                        (rs, rowNum) -> {
                            com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = new com.qskj.get_geo_pg.pojo.RoadNetworkConfig();
                            config.setId(rs.getString("id"));
                            config.setName(rs.getString("name"));
                            config.setRoadTable(rs.getString("road_table"));
                            config.setNodedTable(rs.getString("noded_table"));
                            config.setCenterLng(rs.getDouble("center_lng"));
                            config.setCenterLat(rs.getDouble("center_lat"));
                            config.setDefaultZoom(rs.getInt("default_zoom"));
                            config.setSortOrder(rs.getInt("sort_order"));
                            config.setStatus(rs.getInt("status"));
                            config.setBuildTime(rs.getString("build_time"));
                            return config;
                        }, networkId);
                if (xzqList != null && !xzqList.isEmpty()) {
                    return xzqList.get(0);
                }
            } catch (Exception ignored) {
            }

            // 动态推断表名
            com.qskj.get_geo_pg.pojo.RoadNetworkConfig dynConfig = new com.qskj.get_geo_pg.pojo.RoadNetworkConfig();
            dynConfig.setId(networkId);
            dynConfig.setName(networkId);
            if (networkId.endsWith("_3d") || networkId.contains("3d_")) {
                dynConfig.setRoadTable("\"3d_road\"." + networkId + "_base");
                dynConfig.setNodedTable("\"3d_road\"." + networkId + "_base_noded");
            } else {
                dynConfig.setRoadTable("xzq_road." + networkId + "_base");
                dynConfig.setNodedTable("xzq_road." + networkId + "_base_noded");
            }
            return dynConfig;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除指定行政区路网及其物理表和配置记录
     */
    public boolean deleteRoadNetwork(String networkId) {
        if (networkId == null || networkId.trim().isEmpty()) {
            throw new IllegalArgumentException("删除失败：路网 ID 不能为空");
        }

        com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = getNetworkConfig(networkId);
        if (config == null || !networkId.equalsIgnoreCase(config.getId())) {
            throw new IllegalArgumentException("未找到待删除的行政区路网配置: " + networkId);
        }

        String roadTable = config.getRoadTable();
        String nodedTable = config.getNodedTable();

        if (nodedTable != null && !nodedTable.trim().isEmpty()) {
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + "_vertices_pgr CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + " CASCADE;");
        }
        if (roadTable != null && !roadTable.trim().isEmpty()) {
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + roadTable + " CASCADE;");
        }

        int deletedRows = 0;
        try {
            deletedRows = graphsJdbcTemplate.update("DELETE FROM \"3d_road\".sys_road_network_by_xzq WHERE id = ?", networkId);
        } catch (Exception ignored) {}
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
     * 更新指定行政区路网的显示名称
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
            rows = graphsJdbcTemplate.update("UPDATE \"3d_road\".sys_road_network_by_xzq SET name = ? WHERE id = ?",
                    newName.trim(), networkId);
        } catch (Exception ignored) {}
        if (rows == 0) {
            try {
                rows = graphsJdbcTemplate.update("UPDATE xzq_road.sys_road_network_by_xzq SET name = ? WHERE id = ?",
                        newName.trim(), networkId);
            } catch (Exception ignored) {}
        }
        if (rows == 0) {
            try {
                rows = graphsJdbcTemplate.update("UPDATE xzq_road.sys_road_network SET name = ? WHERE id = ?",
                        newName.trim(), networkId);
            } catch (Exception ignored) {}
        }
        return rows > 0;
    }

    /**
     * 从 graphs 库中查询指定行政区路网的 GeoJSON FeatureCollection 特征数据
     */
    public Object getRoadRange(String networkId, Double minLng, Double minLat, Double maxLng, Double maxLat) {
        com.qskj.get_geo_pg.pojo.RoadNetworkConfig config = getNetworkConfig(networkId);
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
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.get(0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 4. 获取任意指定路网的边界 GeoJSON 与包围盒
     */
    public Map<String, Object> getNetworkBoundary(String networkId) {
        if (networkId == null || networkId.trim().isEmpty())
            return null;

        if (networkId.startsWith("xzq_")) {
            String[] parts = networkId.split("_", 3);
            if (parts.length >= 3) {
                String level = parts[1];
                String featId = parts[2];
                if (featId.endsWith("_3d")) {
                    featId = featId.substring(0, featId.length() - 3);
                }
                Map<String, Object> detail = getXzqDetail(level, featId);
                if (detail != null && detail.get("geojson") != null) {
                    return detail;
                }
            }
        }

        try {
            String roadTable = null;
            try {
                roadTable = graphsJdbcTemplate.queryForObject(
                        "SELECT road_table FROM \"3d_road\".sys_road_network_by_xzq WHERE id = ?",
                        String.class, networkId);
            } catch (Exception e3d) {
                try {
                    roadTable = graphsJdbcTemplate.queryForObject(
                            "SELECT road_table FROM xzq_road.sys_road_network_by_xzq WHERE id = ?",
                            String.class, networkId);
                } catch (Exception e) {
                    try {
                        roadTable = graphsJdbcTemplate.queryForObject(
                                "SELECT road_table FROM xzq_road.sys_road_network WHERE id = ?",
                                String.class, networkId);
                    } catch (Exception e2) {
                        try {
                            roadTable = graphsJdbcTemplate.queryForObject(
                                    "SELECT road_table FROM upload_shp_road.sys_road_network WHERE id = ?",
                                    String.class, networkId);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            if (roadTable != null) {
                String sql = String.format(
                        "SELECT ST_AsGeoJSON(COALESCE(ST_ConcaveHull(ST_Collect(geom), 0.85), ST_ConvexHull(ST_Collect(geom)), ST_Envelope(ST_Collect(geom)))) AS geojson, " +
                                "ST_XMin(ST_Extent(geom)) AS min_x, ST_XMax(ST_Extent(geom)) AS max_x, " +
                                "ST_YMin(ST_Extent(geom)) AS min_y, ST_YMax(ST_Extent(geom)) AS max_y " +
                                "FROM %s WHERE geom IS NOT NULL",
                        roadTable);
                Map<String, Object> row = graphsJdbcTemplate.queryForMap(sql);
                if (row != null && row.get("geojson") != null) {
                    Map<String, Object> res = new HashMap<>();
                    res.put("id", networkId);
                    Double minX = row.get("min_x") != null ? ((Number) row.get("min_x")).doubleValue() : null;
                    Double maxX = row.get("max_x") != null ? ((Number) row.get("max_x")).doubleValue() : null;
                    Double minY = row.get("min_y") != null ? ((Number) row.get("min_y")).doubleValue() : null;
                    Double maxY = row.get("max_y") != null ? ((Number) row.get("max_y")).doubleValue() : null;

                    if (minX != null && maxX != null && minY != null && maxY != null) {
                        res.put("bbox", Arrays.asList(minX, minY, maxX, maxY));
                    }
                    res.put("geojson", row.get("geojson"));
                    return res;
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return null;
    }

    /**
     * 5. 根据选择的行政区划要素与 osm.sc_road 执行空间相交并构建 pgRouting 拓扑路网
     */
    public Map<String, Object> buildNetworkFromXzq(String level, Object featureId, String networkId, String networkName)
            throws Exception {
        if (networkId == null || !networkId.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("路网 Code (ID) 格式不合法，仅支持字母、数字和下划线！");
        }

        String xzqTable = resolveTableName(level);

        String idCol = null;
        try {
            List<Map<String, Object>> cfgList = primaryJdbcTemplate.queryForList(
                    "SELECT id_column FROM sc_xzq.xzq_field_list WHERE level = ?", level);
            if (cfgList != null && !cfgList.isEmpty()) {
                idCol = (String) cfgList.get(0).get("id_column");
            }
        } catch (Exception ignored) {
        }

        if (idCol == null) {
            List<String> idCandidates = Arrays.asList(
                    "村级码", "村级代码", "乡镇码", "乡镇代码", "区县码", "市代码", "gid", "id", "code");
            idCol = findColumn(xzqTable, idCandidates);
        }
        if (idCol == null)
            idCol = "gid";

        if (networkName == null || networkName.trim().isEmpty()) {
            networkName = "路网_" + featureId;
        }

        String roadTable = "osm.sc_road";
        String onewayCol = findColumn(roadTable, Arrays.asList("oneway"));
        String onewaySelect = (onewayCol != null) ? "r." + quoteCol(onewayCol) : "'B'";

        String intersectSql = String.format(
                "SELECT " +
                        "COALESCE(to_jsonb(r)->>'gid', to_jsonb(r)->>'id', to_jsonb(r)->>'orig_id', '0')::int AS orig_id, "
                        +
                        "COALESCE(%s, 'B') AS oneway, " +
                        "ST_AsText((ST_Dump(r.geom)).geom) AS wkt " +
                        "FROM %s r " +
                        "JOIN %s x ON ST_Intersects(r.geom, x.geom) " +
                        "WHERE x.%s::text = ? AND r.geom IS NOT NULL",
                onewaySelect,
                roadTable, xzqTable, quoteCol(idCol));

        List<Map<String, Object>> roadFeatures = primaryJdbcTemplate.queryForList(intersectSql, featureId.toString());

        if (roadFeatures == null || roadFeatures.isEmpty()) {
            throw new IllegalArgumentException("所选行政区划范围内未查找到任何匹配相交的 OSM 路网要素！");
        }

        try {
            graphsJdbcTemplate.execute("ROLLBACK;");
        } catch (Exception ignored) {
        }
        graphsJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS xzq_road;");
        try {
            graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis;");
        } catch (Exception ignored) {
        }
        try {
            graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgrouting;");
        } catch (Exception e) {
            throw new RuntimeException("graphs 数据库未加载 pgRouting 扩展！原因: " + e.getMessage(), e);
        }

        graphsJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS xzq_road.sys_road_network_by_xzq (" +
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

        String baseTable = "xzq_road." + networkId + "_base";
        String nodedTable = "xzq_road." + networkId + "_base_noded";

        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + "_vertices_pgr CASCADE;");
        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + " CASCADE;");
        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + baseTable + " CASCADE;");

        graphsJdbcTemplate.execute("CREATE TABLE " + baseTable + " (" +
                "orig_id INT, " +
                "oneway VARCHAR(10), " +
                "geom geometry(Geometry, 4326)" +
                ");");

        List<Object[]> batchArgs = new ArrayList<>();
        for (Map<String, Object> row : roadFeatures) {
            Integer origId = ((Number) row.get("orig_id")).intValue();
            String oneway = (String) row.get("oneway");
            String wkt = (String) row.get("wkt");
            batchArgs.add(new Object[] { origId, oneway, wkt });
        }

        String insertSql = "INSERT INTO " + baseTable
                + " (orig_id, oneway, geom) VALUES (?, ?, ST_GeomFromText(?, 4326))";
        int batchSize = 2000;
        for (int i = 0; i < batchArgs.size(); i += batchSize) {
            List<Object[]> subList = batchArgs.subList(i, Math.min(i + batchSize, batchArgs.size()));
            graphsJdbcTemplate.batchUpdate(insertSql, subList);
        }

        graphsJdbcTemplate.execute("ALTER TABLE " + baseTable + " ADD COLUMN id SERIAL PRIMARY KEY;");
        graphsJdbcTemplate
                .execute("CREATE INDEX idx_" + networkId + "_base_geom ON " + baseTable + " USING GIST (geom);");

        graphsJdbcTemplate.execute("SELECT pgr_nodeNetwork('" + baseTable + "', 0.00001, 'id', 'geom');");
        createFastTopology("xzq_road", networkId + "_base_noded", networkId);

        graphsJdbcTemplate.execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS cost DOUBLE PRECISION;");
        graphsJdbcTemplate
                .execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS reverse_cost DOUBLE PRECISION;");

        String sqlUpdateCosts = "UPDATE " + nodedTable + " n " +
                "SET cost = CASE WHEN LOWER(COALESCE(l.oneway, 'B')) IN ('b', '0', 'ft', '1', 'f', 'yes', 'true', 'no') THEN ST_Length(n.geom::geography) ELSE -1 END, "
                +
                "    reverse_cost = CASE WHEN LOWER(COALESCE(l.oneway, 'B')) IN ('b', '0', 'tf', '-1', 't', 'no', 'false') THEN ST_Length(n.geom::geography) ELSE -1 END "
                +
                "FROM " + baseTable + " l WHERE n.old_id = l.id";
        graphsJdbcTemplate.execute(sqlUpdateCosts);

        graphsJdbcTemplate
                .execute("CREATE INDEX idx_" + networkId + "_base_noded_geom ON " + nodedTable + " USING GIST (geom);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_base_noded_src ON " + nodedTable + " (source);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_base_noded_tgt ON " + nodedTable + " (target);");

        Map<String, Object> extentMap = graphsJdbcTemplate.queryForMap(
                "SELECT " +
                        "ST_XMin(ST_Extent(geom)) AS min_x, ST_XMax(ST_Extent(geom)) AS max_x, " +
                        "ST_YMin(ST_Extent(geom)) AS min_y, ST_YMax(ST_Extent(geom)) AS max_y " +
                        "FROM " + nodedTable);

        double minX = ((Number) extentMap.get("min_x")).doubleValue();
        double maxX = ((Number) extentMap.get("max_x")).doubleValue();
        double minY = ((Number) extentMap.get("min_y")).doubleValue();
        double maxY = ((Number) extentMap.get("max_y")).doubleValue();

        double centerLng = (minX + maxX) / 2.0;
        double centerLat = (minY + maxY) / 2.0;

        String buildTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sqlUpsertNetwork = "INSERT INTO xzq_road.sys_road_network_by_xzq (id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, build_time) "
                +
                "VALUES (?, ?, ?, ?, ?, ?, 15, 10, 1, NOW()) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "name = EXCLUDED.name, road_table = EXCLUDED.road_table, noded_table = EXCLUDED.noded_table, " +
                "center_lng = EXCLUDED.center_lng, center_lat = EXCLUDED.center_lat, status = 1, build_time = NOW()";

        graphsJdbcTemplate.update(sqlUpsertNetwork, networkId, networkName, baseTable, nodedTable, centerLng,
                centerLat);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "基于【" + networkName + "】相交路网拓扑构建成功！相交弧段: " + roadFeatures.size() + " 条");
        result.put("networkId", networkId);
        result.put("networkName", networkName);
        result.put("centerLng", centerLng);
        result.put("centerLat", centerLat);
        result.put("buildTime", buildTime);
        result.put("bbox", Arrays.asList(minX, minY, maxX, maxY));
        return result;
    }

    /**
     * 6. 根据选择的行政区划要素与 osm.sc_road 执行空间相交，并基于 Layer-Aware Noding 构建带层级(3D立体)拓扑路网
     * 存储于 graphs 数据库的 "3d_road" 模式中。
     */
    public Map<String, Object> buildNetworkWithLevelFromXzq(String level, Object featureId, String networkId, String networkName)
            throws Exception {
        if (networkId == null || !networkId.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("路网 Code (ID) 格式不合法，仅支持字母、数字和下划线！");
        }

        String xzqTable = resolveTableName(level);

        String idCol = null;
        try {
            List<Map<String, Object>> cfgList = primaryJdbcTemplate.queryForList(
                    "SELECT id_column FROM sc_xzq.xzq_field_list WHERE level = ?", level);
            if (cfgList != null && !cfgList.isEmpty()) {
                idCol = (String) cfgList.get(0).get("id_column");
            }
        } catch (Exception ignored) {
        }

        if (idCol == null) {
            List<String> idCandidates = Arrays.asList(
                    "村级码", "村级代码", "乡镇码", "乡镇代码", "区县码", "市代码", "gid", "id", "code");
            idCol = findColumn(xzqTable, idCandidates);
        }
        if (idCol == null)
            idCol = "gid";

        if (networkName == null || networkName.trim().isEmpty()) {
            networkName = "路网_" + featureId;
        }

        String roadTable = "osm.sc_road";
        String onewayCol = findColumn(roadTable, Arrays.asList("oneway"));
        String onewaySelect = (onewayCol != null) ? "r." + quoteCol(onewayCol) : "'B'";

        String bridgeCol = findColumn(roadTable, Arrays.asList("bridge"));
        String bridgeSelect = (bridgeCol != null) ? "r." + quoteCol(bridgeCol) : "'F'";

        String tunnelCol = findColumn(roadTable, Arrays.asList("tunnel"));
        String tunnelSelect = (tunnelCol != null) ? "r." + quoteCol(tunnelCol) : "'F'";

        String layerCol = findColumn(roadTable, Arrays.asList("layer"));
        String layerSelect = (layerCol != null) ? "r." + quoteCol(layerCol) : "0";

        String intersectSql = String.format(
                "SELECT " +
                        "COALESCE(to_jsonb(r)->>'gid', to_jsonb(r)->>'id', to_jsonb(r)->>'orig_id', '0')::int AS orig_id, " +
                        "COALESCE(%s, 'B') AS oneway, " +
                        "COALESCE(%s, 'F') AS bridge, " +
                        "COALESCE(%s, 'F') AS tunnel, " +
                        "COALESCE(%s, 0)::int AS layer, " +
                        "ST_AsText((ST_Dump(r.geom)).geom) AS wkt " +
                        "FROM %s r " +
                        "JOIN %s x ON ST_Intersects(r.geom, x.geom) " +
                        "WHERE x.%s::text = ? AND r.geom IS NOT NULL",
                onewaySelect, bridgeSelect, tunnelSelect, layerSelect,
                roadTable, xzqTable, quoteCol(idCol));

        List<Map<String, Object>> roadFeatures = primaryJdbcTemplate.queryForList(intersectSql, featureId.toString());

        if (roadFeatures == null || roadFeatures.isEmpty()) {
            throw new IllegalArgumentException("所选行政区划范围内未查找到任何匹配相交的 OSM 路网要素！");
        }

        try {
            graphsJdbcTemplate.execute("ROLLBACK;");
        } catch (Exception ignored) {
        }
        ensure3dRoadSchemaAndTable();
        try {
            graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis;");
        } catch (Exception ignored) {
        }
        try {
            graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgrouting;");
        } catch (Exception e) {
            throw new RuntimeException("graphs 数据库未加载 pgRouting 扩展！原因: " + e.getMessage(), e);
        }

        String baseTable = "\"3d_road\"." + networkId + "_base";
        String nodedTable = "\"3d_road\"." + networkId + "_base_noded";

        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + "_vertices_pgr CASCADE;");
        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + " CASCADE;");
        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + baseTable + " CASCADE;");

        graphsJdbcTemplate.execute("CREATE TABLE " + baseTable + " (" +
                "orig_id INT, " +
                "oneway VARCHAR(10), " +
                "bridge VARCHAR(5), " +
                "tunnel VARCHAR(5), " +
                "layer INT, " +
                "geom geometry(Geometry, 4326)" +
                ");");

        List<Object[]> batchArgs = new ArrayList<>();
        for (Map<String, Object> row : roadFeatures) {
            Integer origId = ((Number) row.get("orig_id")).intValue();
            String oneway = (String) row.get("oneway");
            String bridge = (String) row.get("bridge");
            String tunnel = (String) row.get("tunnel");
            Integer layer = ((Number) row.get("layer")).intValue();
            String wkt = (String) row.get("wkt");
            batchArgs.add(new Object[] { origId, oneway, bridge, tunnel, layer, wkt });
        }

        String insertSql = "INSERT INTO " + baseTable
                + " (orig_id, oneway, bridge, tunnel, layer, geom) VALUES (?, ?, ?, ?, ?, ST_GeomFromText(?, 4326))";
        int batchSize = 2000;
        for (int i = 0; i < batchArgs.size(); i += batchSize) {
            List<Object[]> subList = batchArgs.subList(i, Math.min(i + batchSize, batchArgs.size()));
            graphsJdbcTemplate.batchUpdate(insertSql, subList);
        }

        graphsJdbcTemplate.execute("ALTER TABLE " + baseTable + " ADD COLUMN id SERIAL PRIMARY KEY;");
        graphsJdbcTemplate
                .execute("CREATE INDEX idx_" + networkId + "_3d_base_geom ON " + baseTable + " USING GIST (geom);");

        // --- 分层打断 (Layer-Aware Noding) ---
        List<Integer> layers = graphsJdbcTemplate.queryForList(
                "SELECT DISTINCT layer FROM " + baseTable + " ORDER BY layer", Integer.class);

        graphsJdbcTemplate.execute("CREATE TABLE " + nodedTable + " (" +
                "id SERIAL PRIMARY KEY, " +
                "old_id INT, " +
                "orig_id INT, " +
                "oneway VARCHAR(10), " +
                "bridge VARCHAR(5), " +
                "tunnel VARCHAR(5), " +
                "layer INT, " +
                "source BIGINT, " +
                "target BIGINT, " +
                "geom geometry(Geometry, 4326)" +
                ");");

        for (Integer l : layers) {
            String layerSuffix = (l < 0 ? "neg" + Math.abs(l) : "" + l);
            String tmpBase = "\"3d_road\".tmp_" + networkId + "_l" + layerSuffix;
            String tmpNoded = tmpBase + "_noded";

            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpNoded + " CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpBase + " CASCADE;");

            graphsJdbcTemplate.execute("CREATE TABLE " + tmpBase + " AS " +
                    "SELECT * FROM " + baseTable + " WHERE layer = " + l);
            graphsJdbcTemplate.execute("ALTER TABLE " + tmpBase + " ADD PRIMARY KEY (id);");
            // 关键优化 1: 为分层临时表建立 GIST 空间索引，避免 pgr_nodeNetwork 全表 O(N^2) 几何相交扫描
            graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_tmp_l" + layerSuffix + "_geom ON " + tmpBase + " USING GIST (geom);");

            String unquotedTmpBase = "3d_road.tmp_" + networkId + "_l" + layerSuffix;
            graphsJdbcTemplate.execute("SELECT pgr_nodeNetwork('" + unquotedTmpBase + "', 0.00001, 'id', 'geom');");

            String insertFromNodedSql = "INSERT INTO " + nodedTable + " (old_id, orig_id, oneway, bridge, tunnel, layer, geom) " +
                    "SELECT n.old_id, b.orig_id, b.oneway, b.bridge, b.tunnel, b.layer, n.geom " +
                    "FROM " + tmpNoded + " n " +
                    "JOIN " + tmpBase + " b ON n.old_id = b.id";
            graphsJdbcTemplate.execute(insertFromNodedSql);

            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpNoded + " CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpBase + " CASCADE;");
        }

        // --- 全局端点立体拓扑 (关键优化 2: 采用集合式高效拓扑构建，代替单行游标 pgr_createTopology) ---
        createFastTopology("3d_road", networkId + "_base_noded", networkId);

        // --- 路由代价字段 (关键优化 3: 单次计算 ST_Length 避免双向计算重复椭球开销) ---
        graphsJdbcTemplate.execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS cost DOUBLE PRECISION;");
        graphsJdbcTemplate.execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS reverse_cost DOUBLE PRECISION;");

        String sqlUpdateCosts = "UPDATE " + nodedTable + " n " +
                "SET cost = CASE WHEN LOWER(COALESCE(n.oneway, 'B')) IN ('b', '0', 'ft', '1', 'f', 'yes', 'true', 'no') THEN sub.len ELSE -1 END, "
                +
                "    reverse_cost = CASE WHEN LOWER(COALESCE(n.oneway, 'B')) IN ('b', '0', 'tf', '-1', 't', 'no', 'false') THEN sub.len ELSE -1 END "
                +
                "FROM (SELECT id, ST_Length(geom::geography) AS len FROM " + nodedTable + ") sub "
                +
                "WHERE n.id = sub.id";
        graphsJdbcTemplate.execute(sqlUpdateCosts);

        // --- 空间与节点索引 ---
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_geom ON " + nodedTable + " USING GIST (geom);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_src ON " + nodedTable + " (source);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_tgt ON " + nodedTable + " (target);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_layer ON " + nodedTable + " (layer);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_bridge ON " + nodedTable + " (bridge);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_tunnel ON " + nodedTable + " (tunnel);");

        // --- 包围盒与中心点 ---
        Map<String, Object> extentMap = graphsJdbcTemplate.queryForMap(
                "SELECT " +
                        "ST_XMin(ST_Extent(geom)) AS min_x, ST_XMax(ST_Extent(geom)) AS max_x, " +
                        "ST_YMin(ST_Extent(geom)) AS min_y, ST_YMax(ST_Extent(geom)) AS max_y " +
                        "FROM " + nodedTable);

        double minX = ((Number) extentMap.get("min_x")).doubleValue();
        double maxX = ((Number) extentMap.get("max_x")).doubleValue();
        double minY = ((Number) extentMap.get("min_y")).doubleValue();
        double maxY = ((Number) extentMap.get("max_y")).doubleValue();

        double centerLng = (minX + maxX) / 2.0;
        double centerLat = (minY + maxY) / 2.0;

        String buildTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sqlUpsertNetwork = "INSERT INTO \"3d_road\".sys_road_network_by_xzq (" +
                "id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, topo_mode, has_bridge, has_tunnel, build_time) "
                +
                "VALUES (?, ?, ?, ?, ?, ?, 15, 10, 1, 'layered', true, true, NOW()) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "name = EXCLUDED.name, road_table = EXCLUDED.road_table, noded_table = EXCLUDED.noded_table, " +
                "center_lng = EXCLUDED.center_lng, center_lat = EXCLUDED.center_lat, status = 1, " +
                "topo_mode = 'layered', has_bridge = true, has_tunnel = true, build_time = NOW()";

        graphsJdbcTemplate.update(sqlUpsertNetwork, networkId, networkName, baseTable, nodedTable, centerLng, centerLat);

        int totalNodedEdges = graphsJdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + nodedTable, Integer.class);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "基于【" + networkName + "】3D立体分层拓扑路网构建成功！原始弧段: " + roadFeatures.size() + " 条，分层拓扑弧段: " + totalNodedEdges + " 条");
        result.put("networkId", networkId);
        result.put("networkName", networkName);
        result.put("centerLng", centerLng);
        result.put("centerLat", centerLat);
        result.put("buildTime", buildTime);
        result.put("bbox", Arrays.asList(minX, minY, maxX, maxY));
        result.put("schema", "3d_road");
        result.put("topoMode", "layered");
        return result;
    }

    private String quoteCol(String col) {
        if (col == null)
            return "gid";
        return "\"" + col.replace("\"", "") + "\"";
    }

    private String findColumn(String fullTableName, List<String> candidateNames) {
        try {
            String[] parts = fullTableName.split("\\.");
            String schema = parts.length > 1 ? parts[0] : "public";
            String table = parts.length > 1 ? parts[1] : parts[0];

            List<Map<String, Object>> columns = primaryJdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                    schema, table);

            for (String candidate : candidateNames) {
                for (Map<String, Object> col : columns) {
                    String colName = (String) col.get("column_name");
                    if (candidate.equalsIgnoreCase(colName)) {
                        return colName;
                    }
                }
            }
        } catch (Exception e) {
            // Log & ignore
        }
        return null;
    }

    /**
     * 7. 按市级要素构建 2D 平面拓扑路网 (快捷便捷方法)
     */
    public Map<String, Object> buildCityNetwork(Object featureId, String networkId, String networkName) throws Exception {
        return buildNetworkFromXzq("city", featureId, networkId, networkName);
    }

    /**
     * 8. 按市级要素构建 3D 立体分层拓扑路网 (Layer-Aware Noding, 快捷便捷方法)
     */
    public Map<String, Object> buildCityNetworkWithLevel(Object featureId, String networkId, String networkName) throws Exception {
        return buildNetworkWithLevelFromXzq("city", featureId, networkId, networkName);
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String extractGeometryGeoJson(String geoJsonStr) {
        if (geoJsonStr == null || geoJsonStr.trim().isEmpty()) {
            throw new IllegalArgumentException("多边形几何数据不能为空！");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(geoJsonStr.trim());
            if (node.has("geometry")) {
                return node.get("geometry").toString();
            }
            if (node.has("features") && node.get("features").isArray() && node.get("features").size() > 0) {
                JsonNode firstFeature = node.get("features").get(0);
                if (firstFeature.has("geometry")) {
                    return firstFeature.get("geometry").toString();
                }
            }
            return node.toString();
        } catch (Exception e) {
            return geoJsonStr;
        }
    }

    /**
     * 9. 接收前端提交的多边形(polygon)与名称(name)：
     *   (1) 与 mdb1.osm.sc_road 进行空间相交计算，按建立路网规则保存到 mdb1.temp.temp_polygon_roads
     *   (2) 基于相交道路，在 graphs 数据库构建 3D 立体分层拓扑路网 (Layer-Aware Noding, 写入 "3d_road")
     *   (3) 登记至 "3d_road".sys_road_network_by_xzq，可立即用于前端算路和二进制 (.pgrb) 导出
     */
    public Map<String, Object> buildNetworkFromPolygon(String polygonGeoJson, String polygonName, String networkId)
            throws Exception {
        if (polygonGeoJson == null || polygonGeoJson.trim().isEmpty()) {
            throw new IllegalArgumentException("提交的多边形空间数据 (polygon) 不能为空！");
        }

        if (polygonName == null || polygonName.trim().isEmpty()) {
            polygonName = "自定义多边形路网_" + System.currentTimeMillis();
        }
        polygonName = polygonName.trim();

        if (networkId == null || networkId.trim().isEmpty()) {
            networkId = "poly_" + System.currentTimeMillis() + "_3d";
        } else {
            networkId = networkId.trim();
        }

        if (!networkId.matches("[a-zA-Z0-9_]+")) {
            networkId = networkId.replaceAll("[^a-zA-Z0-9_]", "_");
            if (networkId.isEmpty()) {
                networkId = "poly_" + System.currentTimeMillis() + "_3d";
            }
        }

        String geomJson = extractGeometryGeoJson(polygonGeoJson);

        // --- 阶段 1: 在 mdb1 中确立 temp.temp_polygon_roads 并执行相交存表 ---
        primaryJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS temp;");
        primaryJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS temp.temp_polygon_roads (" +
                "id SERIAL PRIMARY KEY, " +
                "orig_id INT, " +
                "oneway VARCHAR(10), " +
                "bridge VARCHAR(5), " +
                "tunnel VARCHAR(5), " +
                "layer INT, " +
                "geom geometry(Geometry, 4326), " +
                "polygon_name VARCHAR(255), " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ");");
        try {
            primaryJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_temp_polygon_roads_geom ON temp.temp_polygon_roads USING GIST (geom);");
        } catch (Exception ignored) {
        }

        // 清理同名旧多边形存表记录
        primaryJdbcTemplate.update("DELETE FROM temp.temp_polygon_roads WHERE polygon_name = ?", polygonName);

        String roadTable = "osm.sc_road";
        String onewayCol = findColumn(roadTable, Arrays.asList("oneway"));
        String onewaySelect = (onewayCol != null) ? "r." + quoteCol(onewayCol) : "'B'";

        String bridgeCol = findColumn(roadTable, Arrays.asList("bridge"));
        String bridgeSelect = (bridgeCol != null) ? "r." + quoteCol(bridgeCol) : "'F'";

        String tunnelCol = findColumn(roadTable, Arrays.asList("tunnel"));
        String tunnelSelect = (tunnelCol != null) ? "r." + quoteCol(tunnelCol) : "'F'";

        String layerCol = findColumn(roadTable, Arrays.asList("layer"));
        String layerSelect = (layerCol != null) ? "r." + quoteCol(layerCol) : "0";

        String insertTempSql = String.format(
                "INSERT INTO temp.temp_polygon_roads (orig_id, oneway, bridge, tunnel, layer, geom, polygon_name) " +
                        "SELECT " +
                        "COALESCE(to_jsonb(r)->>'gid', to_jsonb(r)->>'id', to_jsonb(r)->>'orig_id', '0')::int AS orig_id, " +
                        "COALESCE(%s, 'B') AS oneway, " +
                        "COALESCE(%s, 'F') AS bridge, " +
                        "COALESCE(%s, 'F') AS tunnel, " +
                        "COALESCE(%s, 0)::int AS layer, " +
                        "(ST_Dump(r.geom)).geom AS geom, " +
                        "? AS polygon_name " +
                        "FROM %s r " +
                        "WHERE ST_Intersects(r.geom, ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))) AND r.geom IS NOT NULL",
                onewaySelect, bridgeSelect, tunnelSelect, layerSelect, roadTable);

        int insertedCount = primaryJdbcTemplate.update(insertTempSql, polygonName, geomJson);
        if (insertedCount <= 0) {
            throw new IllegalArgumentException("所绘制的多边形范围内未检索到任何相交的 OSM 道路要素，请调整并扩大绘制区域！");
        }

        // 从 temp.temp_polygon_roads 中读取本次存表的要素
        String selectRoadsSql = "SELECT orig_id, oneway, bridge, tunnel, layer, ST_AsText(geom) AS wkt " +
                "FROM temp.temp_polygon_roads WHERE polygon_name = ?";
        List<Map<String, Object>> roadFeatures = primaryJdbcTemplate.queryForList(selectRoadsSql, polygonName);

        // --- 阶段 2: 在 graphs 数据库中基于相交道路构建 3D 分层拓扑路网 (对标 0907_astar_route.html 逻辑) ---
        try {
            graphsJdbcTemplate.execute("ROLLBACK;");
        } catch (Exception ignored) {
        }
        ensure3dRoadSchemaAndTable();
        try {
            graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis;");
        } catch (Exception ignored) {
        }
        try {
            graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgrouting;");
        } catch (Exception e) {
            throw new RuntimeException("graphs 数据库未加载 pgRouting 扩展！原因: " + e.getMessage(), e);
        }

        String baseTable = "\"3d_road\"." + networkId + "_base";
        String nodedTable = "\"3d_road\"." + networkId + "_base_noded";

        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + "_vertices_pgr CASCADE;");
        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + " CASCADE;");
        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + baseTable + " CASCADE;");

        graphsJdbcTemplate.execute("CREATE TABLE " + baseTable + " (" +
                "orig_id INT, " +
                "oneway VARCHAR(10), " +
                "bridge VARCHAR(5), " +
                "tunnel VARCHAR(5), " +
                "layer INT, " +
                "geom geometry(Geometry, 4326)" +
                ");");

        List<Object[]> batchArgs = new ArrayList<>();
        for (Map<String, Object> row : roadFeatures) {
            Integer origId = ((Number) row.get("orig_id")).intValue();
            String oneway = (String) row.get("oneway");
            String bridge = (String) row.get("bridge");
            String tunnel = (String) row.get("tunnel");
            Integer layer = ((Number) row.get("layer")).intValue();
            String wkt = (String) row.get("wkt");
            batchArgs.add(new Object[] { origId, oneway, bridge, tunnel, layer, wkt });
        }

        String insertBaseSql = "INSERT INTO " + baseTable
                + " (orig_id, oneway, bridge, tunnel, layer, geom) VALUES (?, ?, ?, ?, ?, ST_GeomFromText(?, 4326))";
        int batchSize = 2000;
        for (int i = 0; i < batchArgs.size(); i += batchSize) {
            List<Object[]> subList = batchArgs.subList(i, Math.min(i + batchSize, batchArgs.size()));
            graphsJdbcTemplate.batchUpdate(insertBaseSql, subList);
        }

        graphsJdbcTemplate.execute("ALTER TABLE " + baseTable + " ADD COLUMN id SERIAL PRIMARY KEY;");
        graphsJdbcTemplate
                .execute("CREATE INDEX idx_" + networkId + "_3d_base_geom ON " + baseTable + " USING GIST (geom);");

        // 分层打断 (Layer-Aware Noding)
        List<Integer> layers = graphsJdbcTemplate.queryForList(
                "SELECT DISTINCT layer FROM " + baseTable + " ORDER BY layer", Integer.class);

        graphsJdbcTemplate.execute("CREATE TABLE " + nodedTable + " (" +
                "id SERIAL PRIMARY KEY, " +
                "old_id INT, " +
                "orig_id INT, " +
                "oneway VARCHAR(10), " +
                "bridge VARCHAR(5), " +
                "tunnel VARCHAR(5), " +
                "layer INT, " +
                "source BIGINT, " +
                "target BIGINT, " +
                "geom geometry(Geometry, 4326)" +
                ");");

        for (Integer l : layers) {
            String layerSuffix = (l < 0 ? "neg" + Math.abs(l) : "" + l);
            String tmpBase = "\"3d_road\".tmp_" + networkId + "_l" + layerSuffix;
            String tmpNoded = tmpBase + "_noded";

            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpNoded + " CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpBase + " CASCADE;");

            graphsJdbcTemplate.execute("CREATE TABLE " + tmpBase + " AS " +
                    "SELECT * FROM " + baseTable + " WHERE layer = " + l);
            graphsJdbcTemplate.execute("ALTER TABLE " + tmpBase + " ADD PRIMARY KEY (id);");
            // 关键优化 1: 为分层临时表建立 GIST 空间索引，避免 pgr_nodeNetwork 全表 O(N^2) 几何相交扫描
            graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_tmp_l" + layerSuffix + "_geom ON " + tmpBase + " USING GIST (geom);");

            String unquotedTmpBase = "3d_road.tmp_" + networkId + "_l" + layerSuffix;
            graphsJdbcTemplate.execute("SELECT pgr_nodeNetwork('" + unquotedTmpBase + "', 0.00001, 'id', 'geom');");

            String insertFromNodedSql = "INSERT INTO " + nodedTable + " (old_id, orig_id, oneway, bridge, tunnel, layer, geom) " +
                    "SELECT n.old_id, b.orig_id, b.oneway, b.bridge, b.tunnel, b.layer, n.geom " +
                    "FROM " + tmpNoded + " n " +
                    "JOIN " + tmpBase + " b ON n.old_id = b.id";
            graphsJdbcTemplate.execute(insertFromNodedSql);

            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpNoded + " CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + tmpBase + " CASCADE;");
        }

        // 全局端点立体拓扑 (关键优化 2: 采用集合式高效拓扑构建，代替单行游标 pgr_createTopology)
        createFastTopology("3d_road", networkId + "_base_noded", networkId);

        // 路由通行成本 (关键优化 3: 单次计算 ST_Length 避免双向计算重复椭球开销)
        graphsJdbcTemplate.execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS cost DOUBLE PRECISION;");
        graphsJdbcTemplate.execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS reverse_cost DOUBLE PRECISION;");

        String sqlUpdateCosts = "UPDATE " + nodedTable + " n " +
                "SET cost = CASE WHEN LOWER(COALESCE(n.oneway, 'B')) IN ('b', '0', 'ft', '1', 'f', 'yes', 'true', 'no') THEN sub.len ELSE -1 END, "
                +
                "    reverse_cost = CASE WHEN LOWER(COALESCE(n.oneway, 'B')) IN ('b', '0', 'tf', '-1', 't', 'no', 'false') THEN sub.len ELSE -1 END "
                +
                "FROM (SELECT id, ST_Length(geom::geography) AS len FROM " + nodedTable + ") sub "
                +
                "WHERE n.id = sub.id";
        graphsJdbcTemplate.execute(sqlUpdateCosts);

        // 索引体系
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_geom ON " + nodedTable + " USING GIST (geom);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_src ON " + nodedTable + " (source);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_tgt ON " + nodedTable + " (target);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_layer ON " + nodedTable + " (layer);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_bridge ON " + nodedTable + " (bridge);");
        graphsJdbcTemplate.execute("CREATE INDEX idx_" + networkId + "_3d_noded_tunnel ON " + nodedTable + " (tunnel);");

        // 包围盒与中心点
        Map<String, Object> extentMap = graphsJdbcTemplate.queryForMap(
                "SELECT " +
                        "ST_XMin(ST_Extent(geom)) AS min_x, ST_XMax(ST_Extent(geom)) AS max_x, " +
                        "ST_YMin(ST_Extent(geom)) AS min_y, ST_YMax(ST_Extent(geom)) AS max_y " +
                        "FROM " + nodedTable);

        double minX = ((Number) extentMap.get("min_x")).doubleValue();
        double maxX = ((Number) extentMap.get("max_x")).doubleValue();
        double minY = ((Number) extentMap.get("min_y")).doubleValue();
        double maxY = ((Number) extentMap.get("max_y")).doubleValue();

        double centerLng = (minX + maxX) / 2.0;
        double centerLat = (minY + maxY) / 2.0;

        String buildTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sqlUpsertNetwork = "INSERT INTO \"3d_road\".sys_road_network_by_xzq (" +
                "id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status, topo_mode, has_bridge, has_tunnel, build_time) "
                +
                "VALUES (?, ?, ?, ?, ?, ?, 15, 10, 1, 'layered', true, true, NOW()) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "name = EXCLUDED.name, road_table = EXCLUDED.road_table, noded_table = EXCLUDED.noded_table, " +
                "center_lng = EXCLUDED.center_lng, center_lat = EXCLUDED.center_lat, status = 1, " +
                "topo_mode = 'layered', has_bridge = true, has_tunnel = true, build_time = NOW()";

        graphsJdbcTemplate.update(sqlUpsertNetwork, networkId, polygonName, baseTable, nodedTable, centerLng, centerLat);

        int totalNodedEdges = graphsJdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + nodedTable, Integer.class);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "基于多边形【" + polygonName + "】3D立体拓扑路网构建成功！相交存表道路: " + roadFeatures.size() + " 条，拓扑弧段: " + totalNodedEdges + " 条");
        result.put("networkId", networkId);
        result.put("networkName", polygonName);
        result.put("tempTable", "temp.temp_polygon_roads");
        result.put("roadCount", roadFeatures.size());
        result.put("totalEdges", totalNodedEdges);
        result.put("centerLng", centerLng);
        result.put("centerLat", centerLat);
        result.put("buildTime", buildTime);
        result.put("bbox", Arrays.asList(minX, minY, maxX, maxY));
        result.put("schema", "3d_road");
        result.put("topoMode", "layered");
        return result;
    }

    /**
     * 高性能集合式拓扑构建算法 (基于 PostGIS Set-based 向量运算与网格捕捉)
     * 代替 pgRouting 逐行游标循环的 pgr_createTopology，将十万/百万级拓扑耗时从 6~10 分钟压缩至数秒。
     */
    private void createFastTopology(String schema, String nodedTableName, String networkId) {
        String cleanSchema = schema.replace("\"", "");
        String quotedNodedTable = "\"" + cleanSchema + "\".\"" + nodedTableName + "\"";
        String quotedVerticesTable = "\"" + cleanSchema + "\".\"" + nodedTableName + "_vertices_pgr\"";
        String unquotedNodedTable = cleanSchema + "." + nodedTableName;

        graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + quotedVerticesTable + " CASCADE;");
        graphsJdbcTemplate.execute("CREATE TABLE " + quotedVerticesTable + " (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "cnt INTEGER DEFAULT 0, " +
                "chk INTEGER DEFAULT 0, " +
                "ein INTEGER DEFAULT 0, " +
                "eout INTEGER DEFAULT 0, " +
                "the_geom geometry(Point, 4326)" +
                ");");

        // 1. 高速提取全部起止端点并以 0.00001 (约1米) 容差网格去重入库
        graphsJdbcTemplate.execute("INSERT INTO " + quotedVerticesTable + " (the_geom) " +
                "SELECT DISTINCT ST_SnapToGrid(pt, 0.00001) AS the_geom FROM (" +
                "  SELECT ST_StartPoint(geom) AS pt FROM " + quotedNodedTable + " WHERE geom IS NOT NULL " +
                "  UNION ALL " +
                "  SELECT ST_EndPoint(geom) AS pt FROM " + quotedNodedTable + " WHERE geom IS NOT NULL " +
                ") sub;");

        // 2. 为顶点表创建空间索引
        String vertIdx = "idx_v_" + (networkId.length() > 30 ? networkId.substring(0, 30) : networkId) + "_geom";
        graphsJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + vertIdx + " ON " + quotedVerticesTable + " USING GIST (the_geom);");

        // 3. 确保 source 和 target 字段存在
        graphsJdbcTemplate.execute("ALTER TABLE " + quotedNodedTable + " ADD COLUMN IF NOT EXISTS source BIGINT;");
        graphsJdbcTemplate.execute("ALTER TABLE " + quotedNodedTable + " ADD COLUMN IF NOT EXISTS target BIGINT;");

        // 4. 一次性集合关联更新 source 与 target
        graphsJdbcTemplate.execute("UPDATE " + quotedNodedTable + " n " +
                "SET source = s.id, target = t.id " +
                "FROM " + quotedVerticesTable + " s, " + quotedVerticesTable + " t " +
                "WHERE s.the_geom = ST_SnapToGrid(ST_StartPoint(n.geom), 0.00001) " +
                "  AND t.the_geom = ST_SnapToGrid(ST_EndPoint(n.geom), 0.00001);");

        // 5. 兜底容错：如果极个别复杂几何未被网格捕捉到，调用原生 pgr_createTopology 局部补充
        try {
            Integer unassigned = graphsJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + quotedNodedTable + " WHERE source IS NULL OR target IS NULL", Integer.class);
            if (unassigned != null && unassigned > 0) {
                graphsJdbcTemplate.execute("SELECT pgr_createTopology('" + unquotedNodedTable + "', 0.00001, 'geom', 'id', 'source', 'target', rows_where := 'source IS NULL OR target IS NULL', clean := false);");
            }
        } catch (Exception ignored) {
        }
    }

}
