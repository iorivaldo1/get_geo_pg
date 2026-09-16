package com.qskj.get_geo_pg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class ShpRoadBuildService {

    @Autowired
    @Qualifier("graphsJdbcTemplate")
    private JdbcTemplate graphsJdbcTemplate;

    @Value("${app.shp2pgsql-path:}")
    private String shp2pgsqlPathConfig;

    @Value("${app.temp-dir:}")
    private String tempDirConfig;

    /**
     * 从上传的文件夹构建 pgRouting 路网
     */
    public Map<String, Object> buildRoadNetworkFromFolder(MultipartFile[] files, String networkId, String networkName,
            String encoding) throws Exception {
        Map<String, Object> result = new HashMap<>();

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("上传的文件夹文件为空！");
        }

        if (networkId == null || !networkId.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("路网标识 Code 格式不合法，仅支持字母、数字和下划线！");
        }

        if (networkName == null || networkName.trim().isEmpty()) {
            networkName = networkId;
        }

        if (encoding == null || encoding.trim().isEmpty()) {
            encoding = "GBK";
        }

        // 1. 创建磁盘临时文件夹（优先使用 app.temp-dir 配置的目录，若未配置则使用系统临时目录）
        Path tempDir;
        if (tempDirConfig != null && !tempDirConfig.trim().isEmpty()) {
            Path parentDir = Paths.get(tempDirConfig.trim());
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            tempDir = Files.createTempDirectory(parentDir, "shp_upload_" + networkId + "_");
        } else {
            tempDir = Files.createTempDirectory("shp_upload_" + networkId + "_");
        }
        File shpFile = null;

        try {
            // 保存所有上传的文件到临时目录
            for (MultipartFile file : files) {
                if (file.isEmpty())
                    continue;
                String filename = file.getOriginalFilename();
                if (filename == null)
                    continue;

                Path destination = tempDir.resolve(Paths.get(filename).getFileName().toString());
                file.transferTo(destination.toFile());

                if (filename.toLowerCase().endsWith(".shp")) {
                    shpFile = destination.toFile();
                }
            }

            if (shpFile == null) {
                throw new IllegalArgumentException("上传的文件夹中未找到 `.shp` 格式的空间图形主文件！");
            }

            // 2. 自动检测并初始化数据库 SCHEMA 与 PostGIS / pgRouting 扩展
            try {
                graphsJdbcTemplate.execute("ROLLBACK;");
            } catch (Exception ignored) {
            }

            graphsJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS upload_shp_road;");

            try {
                graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis;");
            } catch (Exception e) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignored) {
                }
            }

            try {
                graphsJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgrouting;");
            } catch (Exception e) {
                try {
                    graphsJdbcTemplate.execute("ROLLBACK;");
                } catch (Exception ignored) {
                }
                throw new RuntimeException(
                        "PostgreSQL 数据库未加载 pgRouting 扩展 (缺少 pgr_nodeNetwork 函数)！请在 Ubuntu 服务器终端安装: apt install -y postgresql-pgrouting，并在 PostgreSQL graphs 数据库中执行: CREATE EXTENSION pgrouting;。原因: "
                                + e.getMessage(),
                        e);
            }

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

            // 导出 SQL 并导入 PostgreSQL 临时表 upload_shp_road.{networkId}_shp
            String shpTableName = "upload_shp_road." + networkId + "_shp";
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");

            String shp2pgsqlCmd = findShp2pgsqlExecutable();
            List<String> command = new ArrayList<>();
            command.add(shp2pgsqlCmd);
            command.add("-s");
            command.add("4326");
            command.add("-W");
            command.add(encoding);
            command.add("-I");
            command.add(shpFile.getAbsolutePath());
            command.add(shpTableName);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new RuntimeException("服务器无法执行 shp2pgsql 转换程序 (尝试路径: " + shp2pgsqlCmd + ")。" +
                        "请确认 shp2pgsql 已添加到 PATH 环境变量，或在 application.properties 中配置 app.shp2pgsql-path。原因: "
                        + e.getMessage(), e);
            }

            StringBuilder sqlBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sqlBuilder.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String sqlStatements = sqlBuilder.toString();

            if (exitCode != 0) {
                // 如果第一次失败且为 GBK，自动尝试 CP936 和 UTF-8 兜底
                if ("GBK".equalsIgnoreCase(encoding)) {
                    sqlStatements = tryShp2pgsqlWithFallback(shp2pgsqlCmd, shpFile, shpTableName, "CP936");
                    if (sqlStatements == null) {
                        sqlStatements = tryShp2pgsqlWithFallback(shp2pgsqlCmd, shpFile, shpTableName, "UTF-8");
                    }
                }
                if (sqlStatements == null || sqlStatements.trim().isEmpty()) {
                    throw new RuntimeException(
                            "运行 shp2pgsql 转换工具失败 (exitCode=" + exitCode + ")，输出信息:\n" + sqlBuilder.toString());
                }
            }

            // 过滤 shp2pgsql 产生的调试信息行 (如 Field ..., Shapefile type: ..., Postgis type: ...)
            String cleanSql = filterSqlStatements(sqlStatements);

            if (cleanSql.trim().isEmpty()) {
                throw new RuntimeException("shp2pgsql 转换出的 SQL 内容为空！输出信息:\n" + sqlBuilder.toString());
            }

            // 执行 SQL 导入 upload_shp_road.{networkId}_shp
            graphsJdbcTemplate.execute(cleanSql);

            // 3. 前置数据严格合规性校验
            // (a) 检查必要字段 (geom 空间几何字段)
            List<Map<String, Object>> columns = graphsJdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'upload_shp_road' AND table_name = ?",
                    networkId + "_shp");
            boolean hasGeom = columns.stream().anyMatch(c -> "geom".equalsIgnoreCase((String) c.get("column_name")));
            if (!hasGeom) {
                graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");
                throw new IllegalArgumentException("❌ SHP 文件校验未通过：属性表缺少必要的空间图形字段 (geom)！");
            }

            // (b) 检查矢量几何类型 (必须为线类型：LINESTRING 或 MULTILINESTRING)
            List<String> geomTypes = graphsJdbcTemplate.queryForList(
                    "SELECT DISTINCT GeometryType(geom) FROM " + shpTableName + " WHERE geom IS NOT NULL",
                    String.class);
            if (geomTypes == null || geomTypes.isEmpty()) {
                graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");
                throw new IllegalArgumentException("❌ SHP 文件校验未通过：图形主文件中未包含任何有效空间要素记录！");
            }

            boolean allLines = geomTypes.stream()
                    .allMatch(t -> "LINESTRING".equalsIgnoreCase(t) || "MULTILINESTRING".equalsIgnoreCase(t));
            if (!allLines) {
                graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");
                throw new IllegalArgumentException(String.format(
                        "❌ SHP 文件校验未通过：必须上传【线要素图层 (PolyLine)】！\n" +
                                "检测到不支持的图形类型: [%s]。\n" +
                                "说明：路网构建与拓扑打断仅适用于道路/水系等线图层 (LineString)，不支持点 (Point) 或面 (Polygon) 图层。",
                        String.join(", ", geomTypes)));
            }

            // (c) 检查坐标系与包围盒坐标范围 (必须为 EPSG:4326 WGS84 经纬度 [-180, 180], [-90, 90])
            Map<String, Object> extentMap = graphsJdbcTemplate.queryForMap(
                    "SELECT " +
                            "ST_XMin(ST_Extent(geom)) AS min_x, ST_XMax(ST_Extent(geom)) AS max_x, " +
                            "ST_YMin(ST_Extent(geom)) AS min_y, ST_YMax(ST_Extent(geom)) AS max_y " +
                            "FROM " + shpTableName);

            if (extentMap.get("min_x") == null || extentMap.get("max_x") == null ||
                    extentMap.get("min_y") == null || extentMap.get("max_y") == null) {
                graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");
                throw new IllegalArgumentException("❌ SHP 文件校验未通过：无法获取图形的有效经纬度空间包围盒边界！");
            }

            double minX = ((Number) extentMap.get("min_x")).doubleValue();
            double maxX = ((Number) extentMap.get("max_x")).doubleValue();
            double minY = ((Number) extentMap.get("min_y")).doubleValue();
            double maxY = ((Number) extentMap.get("max_y")).doubleValue();

            // 校验经度范围 [-180, 180] 和 纬度范围 [-90, 90]
            if (minX < -180.0 || maxX > 180.0 || minY < -90.0 || maxY > 90.0) {
                graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");
                throw new IllegalArgumentException(String.format(
                        "❌ SHP 文件校验未通过：坐标系必须为 WGS84 经纬度 (EPSG:4326)！\n" +
                                "检测到坐标范围: X[%.2f, %.2f], Y[%.2f, %.2f]，疑似平面投影坐标 (如 CGCS2000 / 高斯克吕格 / 3857 墨卡托)。\n" +
                                "解决办法：请先在 GIS 软件 (如 QGIS / ArcGIS) 中重投影为 EPSG:4326 (WGS84 经纬度) 后重新上传！",
                        minX, maxX, minY, maxY));
            }

            // 检查 oneway 字段是否存在
            boolean hasOneway = columns.stream()
                    .anyMatch(c -> "oneway".equalsIgnoreCase((String) c.get("column_name")));

            // 4. 执行 pgRouting 自动化建图逻辑
            String baseTable = "upload_shp_road." + networkId + "_base";
            String nodedTable = "upload_shp_road." + networkId + "_base_noded";

            // 清理旧表
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + "_vertices_pgr CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + nodedTable + " CASCADE;");
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + baseTable + " CASCADE;");

            // (1) ST_Dump 拆线生成 base 表
            String onewaySelect = hasOneway ? "oneway" : "'B' AS oneway";
            String sqlCreateBase = "CREATE TABLE " + baseTable + " AS " +
                    "SELECT COALESCE(to_jsonb(t)->>'gid', to_jsonb(t)->>'id', to_jsonb(t)->>'orig_id', '0')::int AS orig_id, "
                    +
                    onewaySelect + ", " +
                    "(ST_Dump(geom)).geom AS geom " +
                    "FROM " + shpTableName + " t";
            graphsJdbcTemplate.execute(sqlCreateBase);

            // (2) 添加主键与空间索引
            graphsJdbcTemplate.execute("ALTER TABLE " + baseTable + " ADD COLUMN id SERIAL PRIMARY KEY;");
            graphsJdbcTemplate
                    .execute("CREATE INDEX idx_" + networkId + "_base_geom ON " + baseTable + " USING GIST (geom);");

            // (3) pgr_nodeNetwork 打断弧段
            graphsJdbcTemplate.execute("SELECT pgr_nodeNetwork('" + baseTable + "', 0.00001, 'id', 'geom');");

            // (4) pgr_createTopology 构建拓扑
            graphsJdbcTemplate.execute("SELECT pgr_createTopology('" + nodedTable + "', 0.00001, 'geom', 'id');");

            // (5) 添加并计算正反向 cost & reverse_cost
            graphsJdbcTemplate
                    .execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS cost DOUBLE PRECISION;");
            graphsJdbcTemplate
                    .execute("ALTER TABLE " + nodedTable + " ADD COLUMN IF NOT EXISTS reverse_cost DOUBLE PRECISION;");

            String sqlUpdateCosts = "UPDATE " + nodedTable + " n " +
                    "SET cost = CASE WHEN l.oneway IN ('B', '0', 'FT', '1', 'F') OR l.oneway IS NULL THEN ST_Length(n.geom::geography) ELSE -1 END, "
                    +
                    "    reverse_cost = CASE WHEN l.oneway IN ('B', '0', 'TF', '-1', 'T') OR l.oneway IS NULL THEN ST_Length(n.geom::geography) ELSE -1 END "
                    +
                    "FROM " + baseTable + " l WHERE n.old_id = l.id";
            graphsJdbcTemplate.execute(sqlUpdateCosts);

            // (6) 创建 GIST 和 B-Tree 节点索引
            graphsJdbcTemplate.execute(
                    "CREATE INDEX idx_" + networkId + "_base_noded_geom ON " + nodedTable + " USING GIST (geom);");
            graphsJdbcTemplate
                    .execute("CREATE INDEX idx_" + networkId + "_base_noded_src ON " + nodedTable + " (source);");
            graphsJdbcTemplate
                    .execute("CREATE INDEX idx_" + networkId + "_base_noded_tgt ON " + nodedTable + " (target);");

            // 5. 删除临时 shp 表
            graphsJdbcTemplate.execute("DROP TABLE IF EXISTS " + shpTableName + " CASCADE;");

            // 6. 计算中心坐标并注册到 upload_shp_road.sys_road_network
            double centerLng = (minX + maxX) / 2.0;
            double centerLat = (minY + maxY) / 2.0;

            String sqlUpsertNetwork = "INSERT INTO upload_shp_road.sys_road_network (id, name, road_table, noded_table, center_lng, center_lat, default_zoom, sort_order, status) "
                    +
                    "VALUES (?, ?, ?, ?, ?, ?, 15, 10, 1) " +
                    "ON CONFLICT (id) DO UPDATE SET " +
                    "name = EXCLUDED.name, road_table = EXCLUDED.road_table, noded_table = EXCLUDED.noded_table, " +
                    "center_lng = EXCLUDED.center_lng, center_lat = EXCLUDED.center_lat, status = 1";

            graphsJdbcTemplate.update(sqlUpsertNetwork, networkId, networkName, baseTable, nodedTable, centerLng,
                    centerLat);

            result.put("code", 200);
            result.put("msg", "路网构建成功！包含表: " + baseTable + ", " + nodedTable);
            result.put("networkId", networkId);
            result.put("networkName", networkName);
            result.put("centerLng", centerLng);
            result.put("centerLat", centerLat);
            return result;

        } finally {
            // 7. 清理磁盘临时文件
            deleteDirectorySilently(tempDir.toFile());
        }
    }

    private String tryShp2pgsqlWithFallback(String shp2pgsqlCmd, File shpFile, String shpTableName,
            String altEncoding) {
        try {
            List<String> command = new ArrayList<>();
            command.add(shp2pgsqlCmd);
            command.add("-s");
            command.add("4326");
            command.add("-W");
            command.add(altEncoding);
            command.add("-I");
            command.add(shpFile.getAbsolutePath());
            command.add(shpTableName);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sqlBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sqlBuilder.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0 && sqlBuilder.length() > 0) {
                return sqlBuilder.toString();
            }
        } catch (Exception e) {
            // Ignore fallback failure
        }
        return null;
    }

    private String filterSqlStatements(String rawSql) {
        if (rawSql == null)
            return "";
        StringBuilder cleanSql = new StringBuilder();
        String[] lines = rawSql.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("field ") ||
                    lower.startsWith("shapefile type:") ||
                    lower.startsWith("postgis type:") ||
                    lower.startsWith("connecting ") ||
                    lower.startsWith("processing ")) {
                continue; // 忽略非 SQL 的属性类型及说明信息行
            }
            cleanSql.append(line).append("\n");
        }
        return cleanSql.toString();
    }

    private String findShp2pgsqlExecutable() {
        if (shp2pgsqlPathConfig != null && !shp2pgsqlPathConfig.trim().isEmpty()) {
            String customPath = shp2pgsqlPathConfig.trim();
            File f = new File(customPath);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
            return customPath;
        }

        String[] possiblePaths = {
                // Windows 常见目录
                "C:\\Program Files\\PostgreSQL\\16\\bin\\shp2pgsql.exe",
                "C:\\Program Files\\PostgreSQL\\15\\bin\\shp2pgsql.exe",
                "C:\\Program Files\\PostgreSQL\\14\\bin\\shp2pgsql.exe",
                "C:\\Program Files\\PostgreSQL\\13\\bin\\shp2pgsql.exe",
                "C:\\Program Files\\PostgreSQL\\12\\bin\\shp2pgsql.exe",
                "C:\\Program Files\\PostGIS\\bin\\shp2pgsql.exe",
                // Linux / Unix 常见目录
                "/usr/bin/shp2pgsql",
                "/usr/local/bin/shp2pgsql",
                "/usr/pgsql-16/bin/shp2pgsql",
                "/usr/pgsql-15/bin/shp2pgsql",
                "/usr/pgsql-14/bin/shp2pgsql",
                "/usr/pgsql-13/bin/shp2pgsql",
                "/usr/pgsql-12/bin/shp2pgsql",
                "/usr/lib/postgis/shp2pgsql"
        };
        for (String p : possiblePaths) {
            File f = new File(p);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return "shp2pgsql";
    }

    private void deleteDirectorySilently(File dir) {
        if (dir == null || !dir.exists())
            return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectorySilently(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
