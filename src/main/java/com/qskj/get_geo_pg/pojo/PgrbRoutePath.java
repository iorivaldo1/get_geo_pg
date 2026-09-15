package com.qskj.get_geo_pg.pojo;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * PGRB 路径规划结果领域模型 (PGRP 二进制协议规范)
 * 支持毫秒级二进制流打包 (toBinary) 与标准 JSON Map (toResponseMap) 输出
 */
public class PgrbRoutePath implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String MAGIC = "PGRP";
    public static final short CURRENT_VERSION = 1;

    public int code;
    public String message;
    public String networkId;
    public double totalDistance; // 米
    public long costTimeMs;      // 算法耗时
    public int nodeCount;        // 途径拓扑节点数
    public int coordCount;       // 折线形点数
    public long startNodeId;     // 起点原始 PostGIS 节点 ID
    public long endNodeId;       // 终点原始 PostGIS 节点 ID

    public int[] pathNodes;      // 途径内部节点序列
    public int[] coords;         // 定点整数折线坐标 [coordCount * 2] (lngScaled, latScaled)

    private byte[] cachedBinary; // 缓存的二进制流

    public PgrbRoutePath() {}

    /**
     * 将规划路径打包为符合 PGRP 协议的紧凑二进制字节流
     */
    public synchronized byte[] toBinary() {
        if (cachedBinary != null) {
            return cachedBinary;
        }

        // Header: 44B
        // Magic(4B) + Version(2B) + Code(2B) + TotalDistance(8B) + CostTimeMs(4B) +
        // NodeCount(4B) + CoordCount(4B) + StartNodeId(8B) + EndNodeId(8B) = 44B
        // Body: pathNodes(nodeCount * 4B) + coords(coordCount * 8B)
        int totalBytes = 44 + (nodeCount * 4) + (coordCount * 8);
        ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);

        buf.put(MAGIC.getBytes());
        buf.putShort(CURRENT_VERSION);
        buf.putShort((short) code);
        buf.putDouble(totalDistance);
        buf.putInt((int) costTimeMs);
        buf.putInt(nodeCount);
        buf.putInt(coordCount);
        buf.putLong(startNodeId);
        buf.putLong(endNodeId);

        if (pathNodes != null) {
            for (int node : pathNodes) {
                buf.putInt(node);
            }
        }

        if (coords != null) {
            for (int c : coords) {
                buf.putInt(c);
            }
        }

        this.cachedBinary = buf.array();
        return this.cachedBinary;
    }

    /**
     * 转换为 Leaflet 规范的标准 GeoJSON 结构（用于向后兼容 JSON 接口）
     */
    public Map<String, Object> toResponseMap() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", code);
        if (message != null && !message.isEmpty()) {
            res.put("message", message);
        }

        if (code == 200) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalDistance", totalDistance);
            data.put("costTimeMs", costTimeMs);
            data.put("startNode", String.valueOf(startNodeId));
            data.put("endNode", String.valueOf(endNodeId));
            data.put("nodeCount", nodeCount);

            List<List<Double>> coordList = new ArrayList<>(coordCount);
            if (coords != null) {
                for (int i = 0; i < coordCount; i++) {
                    double lng = coords[i * 2] / 1e6;
                    double lat = coords[i * 2 + 1] / 1e6;
                    coordList.add(Arrays.asList(lng, lat));
                }
            }

            Map<String, Object> lineGeom = new LinkedHashMap<>();
            lineGeom.put("type", "LineString");
            lineGeom.put("coordinates", coordList);

            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("type", "Feature");
            feature.put("geometry", lineGeom);
            feature.put("properties", Collections.singletonMap("seq", 0));

            Map<String, Object> fc = new LinkedHashMap<>();
            fc.put("type", "FeatureCollection");
            fc.put("features", Collections.singletonList(feature));

            data.put("geometry", fc);
            res.put("data", data);
        }
        return res;
    }

    public static PgrbRoutePath notFound(String message) {
        PgrbRoutePath p = new PgrbRoutePath();
        p.code = 404;
        p.message = message;
        p.totalDistance = 0;
        p.nodeCount = 0;
        p.coordCount = 0;
        p.toBinary();
        return p;
    }

    public static PgrbRoutePath error(int code, String message) {
        PgrbRoutePath p = new PgrbRoutePath();
        p.code = code;
        p.message = message;
        p.totalDistance = 0;
        p.nodeCount = 0;
        p.coordCount = 0;
        p.toBinary();
        return p;
    }
}
