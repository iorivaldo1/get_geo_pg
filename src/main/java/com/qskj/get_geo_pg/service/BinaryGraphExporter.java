package com.qskj.get_geo_pg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qskj.get_geo_pg.pojo.PgrbBoundaryBinary;
import com.qskj.get_geo_pg.pojo.RoadNetworkConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BinaryGraphExporter {

    @Autowired
    @Qualifier("graphsJdbcTemplate")
    private JdbcTemplate graphsJdbcTemplate;

    @Autowired
    private RouteService routeService;

    @Autowired
    private XzqRoadBuildService xzqRoadBuildService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 辅助数据结构：内存中的边
    private static class TempEdge {
        long origSource;
        long origTarget;
        int newSource;
        int newTarget;
        float cost;
        float reverseCost;
        List<double[]> points; // [[lng, lat], ...]
    }

    /**
     * 导出指定路网为 PGRB 二进制字节流 (v3 格式：包含 CSR 拓扑 + BoundaryPool 纯坐标池矢量边界)
     */
    public byte[] exportNetwork(String networkId) {
        RoadNetworkConfig config = routeService.getNetworkConfig(networkId);
        if (config == null || config.getNodedTable() == null) {
            throw new IllegalArgumentException("未找到有效的路网配置: " + networkId);
        }

        String nodedTable = config.getNodedTable();

        // 1. 查询全量线段数据（按 source 排序以构建前向星）
        String sql = "SELECT n.id, n.source, n.target, " +
                "       COALESCE(n.cost, ST_Length(n.geom::geography)) AS cost, " +
                "       COALESCE(n.reverse_cost, ST_Length(n.geom::geography)) AS reverse_cost, " +
                "       ST_AsText(n.geom) AS geom_wkt " +
                "FROM " + nodedTable + " n " +
                "ORDER BY n.source, n.target";

        List<TempEdge> rawEdges = graphsJdbcTemplate.query(sql, (rs, rowNum) -> {
            TempEdge edge = new TempEdge();
            edge.origSource = rs.getLong("source");
            edge.origTarget = rs.getLong("target");
            edge.cost = (float) rs.getDouble("cost");
            edge.reverseCost = (float) rs.getDouble("reverse_cost");
            String wkt = rs.getString("geom_wkt");
            edge.points = parseWktPoints(wkt);
            return edge;
        });

        if (rawEdges.isEmpty()) {
            throw new IllegalStateException("路网数据表 " + nodedTable + " 中未检索到边数据");
        }

        // 2. 节点离散 ID 重新编号（0 ~ N-1）
        Map<Long, Integer> origToNewMap = new HashMap<>();
        List<Long> newToOrigList = new ArrayList<>();

        for (TempEdge edge : rawEdges) {
            if (!origToNewMap.containsKey(edge.origSource)) {
                origToNewMap.put(edge.origSource, newToOrigList.size());
                newToOrigList.add(edge.origSource);
            }
            if (!origToNewMap.containsKey(edge.origTarget)) {
                origToNewMap.put(edge.origTarget, newToOrigList.size());
                newToOrigList.add(edge.origTarget);
            }
            edge.newSource = origToNewMap.get(edge.origSource);
            edge.newTarget = origToNewMap.get(edge.origTarget);
        }

        int nodeCount = newToOrigList.size();
        int edgeCount = rawEdges.size();

        // 3. 边的前向星排序（按 newSource, newTarget 升序）
        rawEdges.sort(Comparator.comparingInt((TempEdge e) -> e.newSource)
                .thenComparingInt(e -> e.newTarget));

        // 4. 构建前向星 NodeOffsets 数组
        int[] nodeOffsets = new int[nodeCount + 1];
        int currentSource = 0;
        for (int i = 0; i < edgeCount; i++) {
            TempEdge edge = rawEdges.get(i);
            while (currentSource < edge.newSource) {
                currentSource++;
                nodeOffsets[currentSource] = i;
            }
        }
        while (currentSource < nodeCount) {
            currentSource++;
            nodeOffsets[currentSource] = edgeCount;
        }

        // 5. 提取并打平坐标池 CoordPool，并计算 BoundingBox
        List<int[]> coordPool = new ArrayList<>();
        int doubleMinLng = Integer.MAX_VALUE;
        int doubleMinLat = Integer.MAX_VALUE;
        int doubleMaxLng = Integer.MIN_VALUE;
        int doubleMaxLat = Integer.MIN_VALUE;

        int[] edgeCoordStarts = new int[edgeCount];
        for (int i = 0; i < edgeCount; i++) {
            TempEdge edge = rawEdges.get(i);
            edgeCoordStarts[i] = coordPool.size();
            for (double[] pt : edge.points) {
                int lngS = (int) Math.round(pt[0] * 1e6);
                int latS = (int) Math.round(pt[1] * 1e6);
                coordPool.add(new int[]{lngS, latS});

                if (lngS < doubleMinLng) doubleMinLng = lngS;
                if (latS < doubleMinLat) doubleMinLat = latS;
                if (lngS > doubleMaxLng) doubleMaxLng = lngS;
                if (latS > doubleMaxLat) doubleMaxLat = latS;
            }
        }
        int pointCount = coordPool.size();

        // 6. 提取路网对应的多边形矢量边界点池 (BoundaryPool)
        List<List<double[]>> boundaryRings = extractBoundaryRings(networkId);
        int ringCount = boundaryRings.size();
        int boundaryPointCount = 0;
        for (List<double[]> ring : boundaryRings) {
            boundaryPointCount += ring.size();
        }

        // 规范化构建 PGRB v3 统一多环矢量边界（由 Header.version 统摄，直接包含 ringCount、ringSizes 与各环闭合坐标）
        byte[] boundaryBinaryData = null;
        int boundaryPoolSize = 0;
        if (ringCount > 0 && boundaryPointCount > 0) {
            int[] ringSizes = new int[ringCount];
            int[] boundaryCoords = new int[boundaryPointCount * 2];
            int bCoordIdx = 0;
            for (int r = 0; r < ringCount; r++) {
                List<double[]> ring = boundaryRings.get(r);
                ringSizes[r] = ring.size();
                for (double[] pt : ring) {
                    boundaryCoords[bCoordIdx++] = (int) Math.round(pt[0] * 1e6);
                    boundaryCoords[bCoordIdx++] = (int) Math.round(pt[1] * 1e6);
                }
            }
            PgrbBoundaryBinary boundaryObj = new PgrbBoundaryBinary(networkId, ringCount, boundaryPointCount, ringSizes, boundaryCoords);
            boundaryBinaryData = boundaryObj.toBinary();
            boundaryPoolSize = boundaryBinaryData.length;
        }

        // 7. 计算各个数据块的字节偏移量并构建 ByteBuffer (Little-Endian)
        int headerSize = 40;
        int nodeOffsetsSize = (nodeCount + 1) * 4;
        int nodeOffsetsPadding = (nodeOffsetsSize % 8 != 0) ? 4 : 0; // 8 字节对齐补丁
        int edgesSize = edgeCount * 16;
        int coordPoolSize = pointCount * 8;
        // 根据节点数量自适应选择 IdMap 单元素存储宽度 (1B, 2B, 4B)
        short idBytes;
        if (nodeCount <= 255) {
            idBytes = 1;
        } else if (nodeCount <= 65535) {
            idBytes = 2;
        } else {
            idBytes = 4;
        }
        int idMapSize = nodeCount * idBytes;
        int idMapPadding = (idMapSize % 4 != 0) ? (4 - (idMapSize % 4)) : 0; // 保证 BoundaryPool 4 字节对齐

        int totalBytes = headerSize + nodeOffsetsSize + nodeOffsetsPadding + edgesSize + coordPoolSize + idMapSize + idMapPadding + boundaryPoolSize;
        int idMapOffset = headerSize + nodeOffsetsSize + nodeOffsetsPadding + edgesSize + coordPoolSize;

        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // --- 写入 Header (40B) ---
        buffer.put((byte) 'P');
        buffer.put((byte) 'G');
        buffer.put((byte) 'R');
        buffer.put((byte) 'B');                       // magic (4B)
        buffer.putShort((short) 3);                     // version = 3 (2B) 纯坐标池 BoundaryPool 规范
        buffer.putShort(idBytes);                       // flags = idBytes (2B: 记录 1, 2 或 4 字节自适应整型)
        buffer.putInt(nodeCount);                       // nodeCount (4B)
        buffer.putInt(edgeCount);                       // edgeCount (4B)
        buffer.putInt(pointCount);                      // pointCount (4B)
        buffer.putInt(doubleMinLng);                    // bboxMinLng (4B)
        buffer.putInt(doubleMinLat);                    // bboxMinLat (4B)
        buffer.putInt(doubleMaxLng);                    // bboxMaxLng (4B)
        buffer.putInt(doubleMaxLat);                    // bboxMaxLat (4B)
        buffer.putInt(idMapOffset);                     // idMapOffset (4B)

        // --- 写入 NodeOffsets ((N+1) * 4B) ---
        for (int offset : nodeOffsets) {
            buffer.putInt(offset);
        }
        if (nodeOffsetsPadding > 0) {
            buffer.putInt(0);                           // 补全 4 字节使后续 8 字节对齐
        }

        // --- 写入 Edges (M * 16B) ---
        for (int i = 0; i < edgeCount; i++) {
            TempEdge edge = rawEdges.get(i);
            buffer.putInt(edge.newTarget);              // target (4B)
            buffer.putFloat(edge.cost);                 // cost (4B)
            buffer.putFloat(edge.reverseCost);          // reverseCost (4B)
            buffer.putInt(edgeCoordStarts[i]);           // coordStart (4B)
        }

        // --- 写入 CoordPool (P * 8B) ---
        for (int[] pt : coordPool) {
            buffer.putInt(pt[0]);                       // lngScaled (4B)
            buffer.putInt(pt[1]);                       // latScaled (4B)
        }

        // --- 写入 IdMap (N * idBytes) ---
        for (long origId : newToOrigList) {
            if (idBytes == 1) {
                buffer.put((byte) origId);
            } else if (idBytes == 2) {
                buffer.putShort((short) origId);
            } else {
                buffer.putInt((int) origId);
            }
        }
        if (idMapPadding > 0) {
            for (int p = 0; p < idMapPadding; p++) {
                buffer.put((byte) 0);
            }
        }

        // --- 写入 BoundaryPool (PGRB v3 规范：ringCount(4B) + totalPointCount(4B) + ringSizes + coords) ---
        if (boundaryBinaryData != null && boundaryPoolSize > 0) {
            buffer.put(boundaryBinaryData);
        }

        return buffer.array();
    }

    /**
     * 获取指定路网的多边形边界环集合 (支持 Polygon 与 MultiPolygon)
     */
    private List<List<double[]>> extractBoundaryRings(String networkId) {
        List<List<double[]>> rings = new ArrayList<>();
        if (networkId == null || networkId.trim().isEmpty() || xzqRoadBuildService == null) {
            return rings;
        }

        try {
            Map<String, Object> detail = xzqRoadBuildService.getNetworkBoundary(networkId);
            if (detail == null || detail.get("geojson") == null) {
                return rings;
            }

            Object geoObj = detail.get("geojson");
            JsonNode root;
            if (geoObj instanceof String) {
                root = objectMapper.readTree((String) geoObj);
            } else if (geoObj instanceof JsonNode) {
                root = (JsonNode) geoObj;
            } else {
                root = objectMapper.valueToTree(geoObj);
            }

            if (root == null) return rings;

            // 若是 FeatureCollection 或 Feature，提取 geometry
            if (root.has("type")) {
                String type = root.get("type").asText("");
                if ("FeatureCollection".equalsIgnoreCase(type) && root.has("features") && root.get("features").isArray()) {
                    for (JsonNode feat : root.get("features")) {
                        if (feat.has("geometry")) {
                            parseGeometryRings(feat.get("geometry"), rings);
                        }
                    }
                    return rings;
                } else if ("Feature".equalsIgnoreCase(type) && root.has("geometry")) {
                    parseGeometryRings(root.get("geometry"), rings);
                    return rings;
                }
            }

            parseGeometryRings(root, rings);
        } catch (Exception e) {
            System.err.println("[PGRB Exporter] 提取边界多边形异常 (" + networkId + "): " + e.getMessage());
        }

        return rings;
    }

    /**
     * 解析单个 Geometry 节点的 coordinates
     */
    private void parseGeometryRings(JsonNode geomNode, List<List<double[]>> rings) {
        if (geomNode == null || !geomNode.has("type") || !geomNode.has("coordinates")) {
            return;
        }

        String type = geomNode.get("type").asText("");
        JsonNode coords = geomNode.get("coordinates");
        if (coords == null || !coords.isArray()) return;

        if ("Polygon".equalsIgnoreCase(type)) {
            // Polygon: [ [ [lng, lat], ... ], [ hole... ] ]
            for (JsonNode ringNode : coords) {
                List<double[]> ring = parseSingleRing(ringNode);
                if (!ring.isEmpty()) {
                    rings.add(ring);
                }
            }
        } else if ("MultiPolygon".equalsIgnoreCase(type)) {
            // MultiPolygon: [ [ [ [lng, lat], ... ] ], ... ]
            for (JsonNode polyNode : coords) {
                if (polyNode.isArray()) {
                    for (JsonNode ringNode : polyNode) {
                        List<double[]> ring = parseSingleRing(ringNode);
                        if (!ring.isEmpty()) {
                            rings.add(ring);
                        }
                    }
                }
            }
        }
    }

    private List<double[]> parseSingleRing(JsonNode ringNode) {
        List<double[]> ring = new ArrayList<>();
        if (ringNode == null || !ringNode.isArray()) return ring;

        for (JsonNode ptNode : ringNode) {
            if (ptNode.isArray() && ptNode.size() >= 2) {
                double lng = ptNode.get(0).asDouble();
                double lat = ptNode.get(1).asDouble();
                ring.add(new double[]{lng, lat});
            }
        }
        return ring;
    }

    /**
     * 从 WKT LINESTRING 文本解析出坐标点列表 [[lng, lat], ...]
     */
    private List<double[]> parseWktPoints(String wkt) {
        List<double[]> list = new ArrayList<>();
        if (wkt == null || wkt.trim().isEmpty()) {
            return list;
        }

        Pattern pattern = Pattern.compile("(-?\\d+\\.\\d+|-?\\d+)\\s+(-?\\d+\\.\\d+|-?\\d+)");
        Matcher matcher = pattern.matcher(wkt);
        while (matcher.find()) {
            try {
                double lng = Double.parseDouble(matcher.group(1));
                double lat = Double.parseDouble(matcher.group(2));
                list.add(new double[]{lng, lat});
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }
}
