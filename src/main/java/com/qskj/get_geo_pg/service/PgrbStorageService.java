package com.qskj.get_geo_pg.service;

import com.qskj.get_geo_pg.pojo.PgrbFileManage;
import com.qskj.get_geo_pg.pojo.RoadNetworkConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
public class PgrbStorageService {

    @Autowired
    @Qualifier("graphsJdbcTemplate")
    private JdbcTemplate graphsJdbcTemplate;

    @Autowired
    private BinaryGraphExporter binaryGraphExporter;

    @Autowired
    private RouteService routeService;

    @Value("${pgrb.storage.dir:/opt/data/pgrb}")
    private String configuredStorageDir;

    @Value("${pgrb.storage.dir.local:E:/JavaPro/QSKJ/get_geo_pg/data/pgrb}")
    private String localDevStorageDir;

    private Path effectiveStoragePath;

    @PostConstruct
    public void init() {
        resolveStorageDir();
        initTableIfNotExists();
    }

    /**
     * 1. 智能解析并建立服务器存储目录
     */
    public synchronized void resolveStorageDir() {
        String envDir = System.getenv("PGRB_STORAGE_DIR");
        String targetDirStr = configuredStorageDir;

        if (envDir != null && !envDir.trim().isEmpty()) {
            targetDirStr = envDir.trim();
        } else {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                // Windows 环境下如果配置的是 Linux 根路径 (例如 /opt/...)，平滑兼容切换为本地路径
                if (targetDirStr.startsWith("/") || (!targetDirStr.contains(":") && !targetDirStr.startsWith("\\\\"))) {
                    targetDirStr = localDevStorageDir;
                    log.info("[PGRB Storage] 检测到 Windows 开发环境，自动适配为本地路径: {}", targetDirStr);
                }
            }
        }

        try {
            effectiveStoragePath = Paths.get(targetDirStr).toAbsolutePath().normalize();
            if (!Files.exists(effectiveStoragePath)) {
                Files.createDirectories(effectiveStoragePath);
                log.info("[PGRB Storage] 成功创建存储目录: {}", effectiveStoragePath);
            } else {
                log.info("[PGRB Storage] 存储目录就绪: {}", effectiveStoragePath);
            }
        } catch (Exception e) {
            log.error("[PGRB Storage] 创建存储目录失败 [{}]: {}. 若在 Linux 生产服务器，请执行: sudo mkdir -p {} && sudo chmod -R 775 {}",
                    targetDirStr, e.getMessage(), targetDirStr, targetDirStr);
            // 兜底为系统临时目录
            effectiveStoragePath = Paths.get(System.getProperty("java.io.tmpdir"), "pgrb").toAbsolutePath().normalize();
            try {
                Files.createDirectories(effectiveStoragePath);
            } catch (Exception ignored) {
            }
        }
    }

    public Path getEffectiveStoragePath() {
        if (effectiveStoragePath == null) {
            resolveStorageDir();
        }
        return effectiveStoragePath;
    }

    /**
     * 2. 自动检查并创建 graphs 数据库中的管理表 sys_pgrb_file_manage
     */
    public void initTableIfNotExists() {
        try {
            graphsJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS \"3d_road\";");
            String ddl = "CREATE TABLE IF NOT EXISTS \"3d_road\".sys_pgrb_file_manage (\n" +
                    "    id SERIAL PRIMARY KEY,\n" +
                    "    network_id VARCHAR(128) NOT NULL UNIQUE,\n" +
                    "    network_name VARCHAR(255) NOT NULL,\n" +
                    "    level VARCHAR(32),\n" +
                    "    file_name VARCHAR(255) NOT NULL,\n" +
                    "    file_path VARCHAR(512) NOT NULL,\n" +
                    "    file_size BIGINT NOT NULL DEFAULT 0,\n" +
                    "    file_size_fmt VARCHAR(32),\n" +
                    "    node_count INT DEFAULT 0,\n" +
                    "    edge_count INT DEFAULT 0,\n" +
                    "    point_count INT DEFAULT 0,\n" +
                    "    boundary_point_count INT DEFAULT 0,\n" +
                    "    min_lng DOUBLE PRECISION,\n" +
                    "    min_lat DOUBLE PRECISION,\n" +
                    "    max_lng DOUBLE PRECISION,\n" +
                    "    max_lat DOUBLE PRECISION,\n" +
                    "    center_lng DOUBLE PRECISION,\n" +
                    "    center_lat DOUBLE PRECISION,\n" +
                    "    default_zoom INT DEFAULT 15,\n" +
                    "    status INT DEFAULT 1,\n" +
                    "    build_time TIMESTAMP DEFAULT NOW(),\n" +
                    "    updated_at TIMESTAMP DEFAULT NOW(),\n" +
                    "    remark TEXT\n" +
                    ");";
            graphsJdbcTemplate.execute(ddl);
            graphsJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_pgrb_file_netid ON \"3d_road\".sys_pgrb_file_manage (network_id);");
            graphsJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_pgrb_file_level ON \"3d_road\".sys_pgrb_file_manage (level);");
            log.info("[PGRB Storage] sys_pgrb_file_manage 元数据表校验与初始化完成");
        } catch (Exception e) {
            log.warn("[PGRB Storage] 初始化 sys_pgrb_file_manage 表警告: {}", e.getMessage());
            try {
                graphsJdbcTemplate.execute("ROLLBACK;");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 3. 将二进制数据持久化落盘，并解析 Header 元数据入库
     */
    public synchronized PgrbFileManage savePgrbFile(String networkId, String networkName, String level, byte[] binaryData) throws IOException {
        if (networkId == null || binaryData == null || binaryData.length < 40) {
            throw new IllegalArgumentException("PGRB 二进制数据不合法或长度不足 40 字节！");
        }

        // 解析 40 字节二进制头部
        ByteBuffer buf = ByteBuffer.wrap(binaryData).order(ByteOrder.LITTLE_ENDIAN);
        byte b0 = buf.get();
        byte b1 = buf.get();
        byte b2 = buf.get();
        byte b3 = buf.get();
        if (b0 != 'P' || b1 != 'G' || b2 != 'R' || b3 != 'B') {
            throw new IllegalArgumentException("二进制数据魔数不匹配，非标准 PGRB 格式！");
        }

        short version = buf.getShort();
        short flags = buf.getShort();
        int nodeCount = buf.getInt();
        int edgeCount = buf.getInt();
        int pointCount = buf.getInt();
        int bboxMinLng = buf.getInt();
        int bboxMinLat = buf.getInt();
        int bboxMaxLng = buf.getInt();
        int bboxMaxLat = buf.getInt();
        int idMapOffset = buf.getInt();

        double minLng = bboxMinLng / 1000000.0;
        double minLat = bboxMinLat / 1000000.0;
        double maxLng = bboxMaxLng / 1000000.0;
        double maxLat = bboxMaxLat / 1000000.0;
        double centerLng = (minLng + maxLng) / 2.0;
        double centerLat = (minLat + maxLat) / 2.0;

        int boundaryPointCount = 0;
        try {
            int idBytes = (flags == 1 || flags == 2 || flags == 4) ? flags : 8;
            int idMapSize = nodeCount * idBytes;
            int boundaryOffset = (idMapOffset + idMapSize + 3) & ~3;
            if (binaryData.length >= boundaryOffset + 8) {
                if (version >= 3) {
                    if (binaryData.length >= boundaryOffset + 16
                            && buf.get(boundaryOffset) == 'P'
                            && buf.get(boundaryOffset + 1) == 'G'
                            && buf.get(boundaryOffset + 2) == 'B'
                            && buf.get(boundaryOffset + 3) == 'B') {
                        // 兼容过渡版本 PGBB 规范：totalPointCount 在偏移量 12 处
                        boundaryPointCount = buf.getInt(boundaryOffset + 12);
                    } else {
                        // 现行统一 PGRB v3 规范：ringCount (4B) + totalPointCount (4B)
                        boundaryPointCount = buf.getInt(boundaryOffset + 4);
                    }
                } else if (version == 2) {
                    buf.position(boundaryOffset);
                    boundaryPointCount = buf.getInt();
                }
            }
        } catch (Exception ignored) {
        }

        long fileSize = binaryData.length;
        String fileSizeFmt = formatFileSize(fileSize);
        String fileName = networkId + ".pgrb";

        Path saveDir = getEffectiveStoragePath();
        Path filePath = saveDir.resolve(fileName);
        Files.write(filePath, binaryData);
        log.info("[PGRB Storage] 文件落盘成功: {}, 大小: {}", filePath.toAbsolutePath(), fileSizeFmt);

        String normalizedLevel = (level != null && !level.trim().isEmpty()) ? level.trim().toLowerCase() : "county";

        String sql = "INSERT INTO \"3d_road\".sys_pgrb_file_manage (" +
                "network_id, network_name, level, file_name, file_path, file_size, file_size_fmt, " +
                "node_count, edge_count, point_count, boundary_point_count, min_lng, min_lat, max_lng, max_lat, " +
                "center_lng, center_lat, default_zoom, status, build_time, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 15, 1, NOW(), NOW()) " +
                "ON CONFLICT (network_id) DO UPDATE SET " +
                "network_name = EXCLUDED.network_name, level = EXCLUDED.level, file_name = EXCLUDED.file_name, " +
                "file_path = EXCLUDED.file_path, file_size = EXCLUDED.file_size, file_size_fmt = EXCLUDED.file_size_fmt, " +
                "node_count = EXCLUDED.node_count, edge_count = EXCLUDED.edge_count, point_count = EXCLUDED.point_count, " +
                "boundary_point_count = EXCLUDED.boundary_point_count, min_lng = EXCLUDED.min_lng, min_lat = EXCLUDED.min_lat, " +
                "max_lng = EXCLUDED.max_lng, max_lat = EXCLUDED.max_lat, center_lng = EXCLUDED.center_lng, center_lat = EXCLUDED.center_lat, " +
                "status = 1, updated_at = NOW()";

        graphsJdbcTemplate.update(sql, networkId, networkName, normalizedLevel, fileName, filePath.toAbsolutePath().toString(),
                fileSize, fileSizeFmt, nodeCount, edgeCount, pointCount, boundaryPointCount,
                minLng, minLat, maxLng, maxLat, centerLng, centerLat);

        PgrbFileManage item = new PgrbFileManage();
        item.setNetworkId(networkId);
        item.setNetworkName(networkName);
        item.setLevel(normalizedLevel);
        item.setFileName(fileName);
        item.setFilePath(filePath.toAbsolutePath().toString());
        item.setFileSize(fileSize);
        item.setFileSizeFmt(fileSizeFmt);
        item.setNodeCount(nodeCount);
        item.setEdgeCount(edgeCount);
        item.setPointCount(pointCount);
        item.setBoundaryPointCount(boundaryPointCount);
        item.setMinLng(minLng);
        item.setMinLat(minLat);
        item.setMaxLng(maxLng);
        item.setMaxLat(maxLat);
        item.setCenterLng(centerLng);
        item.setCenterLat(centerLat);
        item.setDefaultZoom(15);
        item.setStatus(1);
        return item;
    }

    /**
     * 4. 获取路网二进制文件列表 (支持级别与关键字过滤)
     */
    public List<PgrbFileManage> getPgrbFileList(String level, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT id, network_id, network_name, level, file_name, file_path, " +
                "file_size, file_size_fmt, node_count, edge_count, point_count, boundary_point_count, " +
                "min_lng, min_lat, max_lng, max_lat, center_lng, center_lat, default_zoom, status, " +
                "to_char(COALESCE(build_time, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS build_time, " +
                "to_char(COALESCE(updated_at, NOW()), 'YYYY-MM-DD HH24:MI:SS') AS updated_at, remark " +
                "FROM \"3d_road\".sys_pgrb_file_manage WHERE status = 1 ");

        List<Object> params = new ArrayList<>();
        if (level != null && !level.trim().isEmpty() && !"all".equalsIgnoreCase(level)) {
            sql.append("AND level = ? ");
            params.add(level.trim().toLowerCase());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append("AND (network_id ILIKE ? OR network_name ILIKE ?) ");
            String kw = "%" + keyword.trim() + "%";
            params.add(kw);
            params.add(kw);
        }
        sql.append("ORDER BY updated_at DESC, id DESC");

        List<PgrbFileManage> list = graphsJdbcTemplate.query(sql.toString(), fileRowMapper, params.toArray());

        // 如果数据库管理表暂无记录，自动从 3d_road.sys_road_network_by_xzq 同步探测已有的路网拓扑
        if (list.isEmpty()) {
            autoSyncExistingNetworks();
            list = graphsJdbcTemplate.query(sql.toString(), fileRowMapper, params.toArray());
        }

        return list;
    }

    /**
     * 5. 获取二进制字节流（若磁盘文件不存在或为空，自动触发高可用重新编译落盘并返回）
     */
    public byte[] getPgrbFileBytes(String networkId) throws Exception {
        if (networkId == null || networkId.trim().isEmpty()) {
            throw new IllegalArgumentException("networkId 不能为空！");
        }

        Path saveDir = getEffectiveStoragePath();
        Path filePath = saveDir.resolve(networkId + ".pgrb");

        if (Files.exists(filePath) && Files.size(filePath) > 40) {
            return Files.readAllBytes(filePath);
        }

        log.info("[PGRB Storage] 磁盘文件不存在或为空，触发自动自愈编译: {}", networkId);
        byte[] binaryData = binaryGraphExporter.exportNetwork(networkId);
        String name = networkId;
        String level = "county";
        try {
            RoadNetworkConfig cfg = routeService.getNetworkConfig(networkId);
            if (cfg != null && cfg.getName() != null) {
                name = cfg.getName();
            }
        } catch (Exception ignored) {
        }
        savePgrbFile(networkId, name, level, binaryData);
        return binaryData;
    }

    /**
     * 6. 重新编译指定路网二进制文件并刷新落盘
     */
    public PgrbFileManage recompilePgrb(String networkId) throws Exception {
        return recompilePgrb(networkId, null);
    }

    public PgrbFileManage recompilePgrb(String networkId, String customLevel) throws Exception {
        byte[] binaryData = binaryGraphExporter.exportNetwork(networkId);

        String networkName = networkId;
        String level = (customLevel != null && !customLevel.trim().isEmpty()) ? customLevel.trim().toLowerCase() : "county";

        try {
            List<Map<String, Object>> rows = graphsJdbcTemplate.queryForList(
                    "SELECT network_name, level FROM \"3d_road\".sys_pgrb_file_manage WHERE network_id = ?", networkId);
            if (!rows.isEmpty()) {
                if (rows.get(0).get("network_name") != null) networkName = (String) rows.get(0).get("network_name");
                if (rows.get(0).get("level") != null && (customLevel == null || customLevel.trim().isEmpty())) {
                    level = (String) rows.get(0).get("level");
                }
            } else {
                RoadNetworkConfig cfg = routeService.getNetworkConfig(networkId);
                if (cfg != null && cfg.getName() != null) networkName = cfg.getName();
                if (customLevel == null || customLevel.trim().isEmpty()) {
                    if (networkId.startsWith("shp_") || networkId.contains("shp") || !networkId.startsWith("xzq_")) {
                        level = "shp";
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return savePgrbFile(networkId, networkName, level, binaryData);
    }

    /**
     * 7. 删除路网二进制文件（可选是否级联删除物理拓扑表）
     */
    public boolean deletePgrbFile(String networkId, boolean deleteDbTable) {
        try {
            Path filePath = getEffectiveStoragePath().resolve(networkId + ".pgrb");
            try {
                Files.deleteIfExists(filePath);
                log.info("[PGRB Storage] 成功删除物理文件: {}", filePath);
            } catch (Exception e) {
                log.warn("[PGRB Storage] 删除磁盘文件异常: {}", e.getMessage());
            }

            // 删除管理表记录
            graphsJdbcTemplate.update("DELETE FROM \"3d_road\".sys_pgrb_file_manage WHERE network_id = ?", networkId);
            graphsJdbcTemplate.update("DELETE FROM \"3d_road\".sys_road_network_by_xzq WHERE id = ?", networkId);

            if (deleteDbTable) {
                log.info("[PGRB Storage] 管理员指定级联清空底层物理拓扑表: {}", networkId);
                String safeId = networkId.replace("\"", "");
                // 1. 清理 3d_road 下的物理表
                graphsJdbcTemplate.execute(String.format("DROP TABLE IF EXISTS \"3d_road\".\"%s_base\" CASCADE;", safeId));
                graphsJdbcTemplate.execute(String.format("DROP TABLE IF EXISTS \"3d_road\".\"%s_base_noded\" CASCADE;", safeId));
                graphsJdbcTemplate.execute(String.format("DROP TABLE IF EXISTS \"3d_road\".\"%s_base_noded_vertices_pgr\" CASCADE;", safeId));
                // 2. 清理 upload_shp_road 下的物理表及注册记录
                graphsJdbcTemplate.execute(String.format("DROP TABLE IF EXISTS upload_shp_road.\"%s_base\" CASCADE;", safeId));
                graphsJdbcTemplate.execute(String.format("DROP TABLE IF EXISTS upload_shp_road.\"%s_base_noded\" CASCADE;", safeId));
                graphsJdbcTemplate.execute(String.format("DROP TABLE IF EXISTS upload_shp_road.\"%s_base_noded_vertices_pgr\" CASCADE;", safeId));
                graphsJdbcTemplate.update("DELETE FROM upload_shp_road.sys_road_network WHERE id = ?", networkId);
            }
            return true;
        } catch (Exception e) {
            log.error("[PGRB Storage] 删除路网失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 8. 修改路网名称
     */
    public boolean updateNetworkName(String networkId, String newName) {
        try {
            graphsJdbcTemplate.update("UPDATE \"3d_road\".sys_pgrb_file_manage SET network_name = ?, updated_at = NOW() WHERE network_id = ?", newName, networkId);
            graphsJdbcTemplate.update("UPDATE \"3d_road\".sys_road_network_by_xzq SET name = ? WHERE id = ?", newName, networkId);
            return true;
        } catch (Exception e) {
            log.error("[PGRB Storage] 修改路网名称失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 自动探测已有路网并入库生成 PGRB
     */
    private void autoSyncExistingNetworks() {
        try {
            String querySql = "SELECT id, name, road_table, noded_table FROM \"3d_road\".sys_road_network_by_xzq WHERE status = 1";
            List<Map<String, Object>> rows = graphsJdbcTemplate.queryForList(querySql);
            for (Map<String, Object> r : rows) {
                String netId = (String) r.get("id");
                String netName = (String) r.get("name");
                try {
                    log.info("[PGRB Storage] 检测到历史拓扑表，自动编译补齐 PGRB 文件: {}", netId);
                    byte[] bytes = binaryGraphExporter.exportNetwork(netId);
                    String lvl = "county";
                    if (netId.contains("city")) lvl = "city";
                    else if (netId.contains("town")) lvl = "town";
                    else if (netId.contains("village")) lvl = "village";
                    savePgrbFile(netId, netName, lvl, bytes);
                } catch (Exception e) {
                    log.warn("[PGRB Storage] 自动补偿历史拓扑 [{}] 失败: {}", netId, e.getMessage());
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.US, "%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private final RowMapper<PgrbFileManage> fileRowMapper = (rs, rowNum) -> {
        PgrbFileManage item = new PgrbFileManage();
        item.setId(rs.getInt("id"));
        item.setNetworkId(rs.getString("network_id"));
        item.setNetworkName(rs.getString("network_name"));
        item.setLevel(rs.getString("level"));
        item.setFileName(rs.getString("file_name"));
        item.setFilePath(rs.getString("file_path"));
        item.setFileSize(rs.getLong("file_size"));
        item.setFileSizeFmt(rs.getString("file_size_fmt"));
        item.setNodeCount(rs.getInt("node_count"));
        item.setEdgeCount(rs.getInt("edge_count"));
        item.setPointCount(rs.getInt("point_count"));
        item.setBoundaryPointCount(rs.getInt("boundary_point_count"));
        item.setMinLng(rs.getDouble("min_lng"));
        item.setMinLat(rs.getDouble("min_lat"));
        item.setMaxLng(rs.getDouble("max_lng"));
        item.setMaxLat(rs.getDouble("max_lat"));
        item.setCenterLng(rs.getDouble("center_lng"));
        item.setCenterLat(rs.getDouble("center_lat"));
        item.setDefaultZoom(rs.getInt("default_zoom"));
        item.setStatus(rs.getInt("status"));
        item.setBuildTime(rs.getString("build_time"));
        item.setUpdatedAt(rs.getString("updated_at"));
        item.setRemark(rs.getString("remark"));
        return item;
    };
}
