package com.qskj.get_geo_pg.service;

import com.qskj.get_geo_pg.pojo.PgrbRoutePath;
import com.qskj.get_geo_pg.util.pgrb.PgrbGraph;
import com.qskj.get_geo_pg.util.pgrb.PgrbGraphEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PGRB 服务端内存常驻算路服务 (支持 PGBB 边界与 PGRP 路径双二进制协议)
 */
@Service
public class PgrbRouterService {
    private static final Logger log = LoggerFactory.getLogger(PgrbRouterService.class);

    @Autowired
    private PgrbStorageService pgrbStorageService;

    // 内存常驻路网图缓存池
    private final Map<String, PgrbGraph> graphCache = new ConcurrentHashMap<>();

    /**
     * 获取或从磁盘加载图至堆内存 (线程安全)
     */
    public PgrbGraph getOrLoadGraph(String networkId) {
        if (networkId == null || networkId.trim().isEmpty()) {
            return null;
        }
        String cleanId = networkId.trim();
        return graphCache.computeIfAbsent(cleanId, id -> {
            try {
                long t0 = System.currentTimeMillis();
                byte[] bytes = pgrbStorageService.getPgrbFileBytes(id);
                if (bytes == null || bytes.length == 0) {
                    log.warn("[PGRB-ROUTER] 未获取到有效路网二进制数据: {}", id);
                    return null;
                }
                PgrbGraph g = PgrbGraphEngine.loadFromBytes(id, bytes);
                long t1 = System.currentTimeMillis();
                log.info("[PGRB-ROUTER] ✅ 成功加载路网【{}】至 Java 堆内存! 节点: {}, 边: {}, 边界点: {}, 耗时: {} ms",
                        id, g.nodeCount, g.edgeCount, (g.boundary != null ? g.boundary.totalPointCount : 0), (t1 - t0));
                return g;
            } catch (Exception e) {
                log.error("[PGRB-ROUTER] 加载路网【{}】失败: {}", id, e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * 获取边界紧凑二进制流 (PGBB 协议规范，仅约 30KB)
     */
    public byte[] getBoundaryBinary(String networkId) {
        PgrbGraph g = getOrLoadGraph(networkId);
        if (g == null || g.boundary == null) {
            return new byte[0];
        }
        return g.boundary.toBinary();
    }

    /**
     * 清除指定路网的内存缓存（当文件重新编译或删除时调用）
     */
    public void evictCache(String networkId) {
        if (networkId != null) {
            graphCache.remove(networkId.trim());
            log.info("[PGRB-ROUTER] 🔄 已清除路网【{}】的内存常驻缓存", networkId);
        }
    }

    /**
     * 清空所有内存图缓存
     */
    public void clearAllCache() {
        graphCache.clear();
        log.info("[PGRB-ROUTER] 🔄 已清空全部路网内存缓存");
    }

    /**
     * 获取纯净轻量元数据 (剥离庞大的 boundaryGeoJSON，体积缩减至 < 1KB)
     */
    public Map<String, Object> getNetworkMeta(String networkId) {
        PgrbGraph g = getOrLoadGraph(networkId);
        if (g == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 404);
            err.put("message", "路网文件不存在或尚未生成: " + networkId);
            return err;
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("networkId", g.networkId);
        data.put("version", g.version);
        data.put("nodeCount", g.nodeCount);
        data.put("edgeCount", g.edgeCount);
        data.put("pointCount", g.pointCount);
        data.put("boundaryPointCount", g.boundary != null ? g.boundary.totalPointCount : 0);
        data.put("boundaryRingCount", g.boundary != null ? g.boundary.ringCount : 0);

        Map<String, Object> bbox = new LinkedHashMap<>();
        bbox.put("minLng", g.minLng);
        bbox.put("minLat", g.minLat);
        bbox.put("maxLng", g.maxLng);
        bbox.put("maxLat", g.maxLat);
        data.put("bbox", bbox);

        res.put("data", data);
        return res;
    }

    /**
     * 单路径规划 (1对1，返回强类型 PgrbRoutePath 领域对象)
     */
    public PgrbRoutePath planSingleRoute(String networkId, double startLng, double startLat, double endLng, double endLat, boolean directed) {
        long t0 = System.currentTimeMillis();
        PgrbGraph g = getOrLoadGraph(networkId);
        if (g == null) {
            return PgrbRoutePath.notFound("未找到路网文件: " + networkId);
        }

        PgrbRoutePath path = PgrbGraphEngine.planRouteWithSnap(g, startLng, startLat, endLng, endLat, directed);
        long t1 = System.currentTimeMillis();
        path.costTimeMs = (t1 - t0);
        path.toBinary(); // 预热生成 cachedBinary
        return path;
    }

    /**
     * 批量路径规划 (1对N 或 N对1)
     */
    public Map<String, Object> planBatchRoutes(String networkId, String mode, double centerLng, double centerLat, List<Map<String, Object>> points, boolean directed) {
        long t0 = System.currentTimeMillis();
        PgrbGraph g = getOrLoadGraph(networkId);
        if (g == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 404);
            err.put("message", "未找到路网文件: " + networkId);
            return err;
        }

        List<Map<String, Object>> routes = new ArrayList<>();
        if (points != null) {
            for (int i = 0; i < points.size(); i++) {
                Map<String, Object> pt = points.get(i);
                double pLng = Double.parseDouble(String.valueOf(pt.get("lng")));
                double pLat = Double.parseDouble(String.valueOf(pt.get("lat")));
                String ptId = pt.containsKey("id") ? String.valueOf(pt.get("id")) : ("pt_" + i);

                PgrbRoutePath singleRes;
                if ("n_to_1".equalsIgnoreCase(mode)) {
                    singleRes = PgrbGraphEngine.planRouteWithSnap(g, pLng, pLat, centerLng, centerLat, directed);
                } else {
                    singleRes = PgrbGraphEngine.planRouteWithSnap(g, centerLng, centerLat, pLng, pLat, directed);
                }

                if (singleRes != null && singleRes.code == 200) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("idx", i);
                    item.put("id", ptId);
                    item.put("point", pt);
                    item.put("totalDistance", singleRes.totalDistance);
                    item.put("startNode", String.valueOf(singleRes.startNodeId));
                    item.put("endNode", String.valueOf(singleRes.endNodeId));
                    Map<String, Object> respMap = singleRes.toResponseMap();
                    if (respMap.containsKey("data")) {
                        Map<String, Object> dt = (Map<String, Object>) respMap.get("data");
                        item.put("geometry", dt.get("geometry"));
                    }
                    routes.add(item);
                }
            }
        }

        long t1 = System.currentTimeMillis();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", routes.size());
        data.put("routes", routes);
        data.put("costTimeMs", (t1 - t0));
        res.put("data", data);
        return res;
    }
}
