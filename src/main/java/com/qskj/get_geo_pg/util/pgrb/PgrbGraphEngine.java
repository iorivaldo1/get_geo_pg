package com.qskj.get_geo_pg.util.pgrb;

import com.qskj.get_geo_pg.pojo.PgrbBoundaryBinary;
import com.qskj.get_geo_pg.pojo.PgrbRoutePath;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * PGRB 高性能图论计算与 A* 寻径引擎 (配合 PgrbBoundaryBinary 与 PgrbRoutePath 强类型领域模型)
 */
public class PgrbGraphEngine {

    // ==========================================
    // 二进制流解码与内存图模型构建
    // ==========================================

    public static PgrbGraph loadFromBytes(String networkId, byte[] buffer) {
        if (buffer == null || buffer.length < 40) {
            throw new IllegalArgumentException("无效的 PGRB 文件流: 长度小于 40 字节");
        }

        ByteBuffer buf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magicBytes = new byte[4];
        buf.get(magicBytes);
        String magic = new String(magicBytes);
        if (!"PGRB".equals(magic)) {
            throw new IllegalArgumentException("非法的 PGRB 魔数标识: " + magic);
        }

        PgrbGraph g = new PgrbGraph();
        g.networkId = networkId;
        g.version = buf.getShort() & 0xFFFF;
        int flags = buf.getShort() & 0xFFFF;
        g.nodeCount = buf.getInt();
        g.edgeCount = buf.getInt();
        g.pointCount = buf.getInt();

        g.minLngS = buf.getInt();
        g.minLatS = buf.getInt();
        g.maxLngS = buf.getInt();
        g.maxLatS = buf.getInt();

        g.minLng = g.minLngS / 1e6;
        g.minLat = g.minLatS / 1e6;
        g.maxLng = g.maxLngS / 1e6;
        g.maxLat = g.maxLatS / 1e6;

        int idMapOffset = buf.getInt();

        // 1. NodeOffsets: 紧随 40B Header
        g.nodeOffsets = new int[g.nodeCount + 1];
        buf.position(40);
        for (int i = 0; i <= g.nodeCount; i++) {
            g.nodeOffsets[i] = buf.getInt();
        }

        // 2. Edges: M * 16B
        int coordPoolStart = idMapOffset - g.pointCount * 8;
        int edgesStart = coordPoolStart - g.edgeCount * 16;

        g.edgesTarget = new int[g.edgeCount];
        g.edgesCost = new float[g.edgeCount];
        g.edgesReverseCost = new float[g.edgeCount];
        g.edgesCoordStart = new int[g.edgeCount];

        for (int i = 0; i < g.edgeCount; i++) {
            int eOff = edgesStart + i * 16;
            g.edgesTarget[i] = buf.getInt(eOff);
            g.edgesCost[i] = buf.getFloat(eOff + 4);
            g.edgesReverseCost[i] = buf.getFloat(eOff + 8);
            g.edgesCoordStart[i] = buf.getInt(eOff + 12);
        }

        // 3. CoordPool: P * 8B (lngS, latS)
        g.coordPool = new int[g.pointCount * 2];
        buf.position(coordPoolStart);
        for (int i = 0; i < g.pointCount * 2; i++) {
            g.coordPool[i] = buf.getInt();
        }

        // 4. IdMap: N * idBytes (自适应支持 1B/2B/4B/8B，flags 记录存储宽度)
        int idBytes = (flags == 1 || flags == 2 || flags == 4) ? flags : 8;
        g.idMap = new long[g.nodeCount];
        buf.position(idMapOffset);
        for (int i = 0; i < g.nodeCount; i++) {
            if (idBytes == 1) {
                g.idMap[i] = buf.get() & 0xFFL;
            } else if (idBytes == 2) {
                g.idMap[i] = buf.getShort() & 0xFFFFL;
            } else if (idBytes == 4) {
                g.idMap[i] = buf.getInt() & 0xFFFFFFFFL;
            } else {
                g.idMap[i] = buf.getLong();
            }
        }

        // 5. 建立每个节点的首坐标索引查找表
        g.nodeFirstCoordIdx = new int[g.nodeCount];
        Arrays.fill(g.nodeFirstCoordIdx, -1);
        for (int u = 0; u < g.nodeCount; u++) {
            int sEdge = g.nodeOffsets[u];
            int eEdge = g.nodeOffsets[u + 1];
            for (int i = sEdge; i < eEdge; i++) {
                int target = g.edgesTarget[i];
                int cStart = g.edgesCoordStart[i];
                int cEnd = (i + 1 < g.edgeCount) ? g.edgesCoordStart[i + 1] : g.pointCount;

                if (g.nodeFirstCoordIdx[u] == -1 && cStart < cEnd) {
                    g.nodeFirstCoordIdx[u] = cStart;
                }
                if (g.nodeFirstCoordIdx[target] == -1 && cEnd > cStart) {
                    g.nodeFirstCoordIdx[target] = cEnd - 1;
                }
            }
        }

        // 6. 构建双向有向图前向星结构
        buildDirectedEdges(g);

        // 7. 构建 128x128 空间网格索引
        buildSpatialGridIndex(g);

        // 8. 解析或构建独立空间边界对象 (PgrbBoundaryBinary)
        PgrbBoundaryBinary boundary = null;
        if (g.version >= 2) {
            int boundaryOffset = (idMapOffset + g.nodeCount * idBytes + 3) & ~3;
            boundary = PgrbBoundaryBinary.parseFromPgrbBuffer(networkId, g.version, buf, buffer.length, boundaryOffset);
        }

        if (boundary == null) {
            boundary = PgrbBoundaryBinary.fromBBox(networkId, g.minLngS, g.minLatS, g.maxLngS, g.maxLatS);
        }
        g.boundary = boundary;

        return g;
    }

    private static void buildDirectedEdges(PgrbGraph g) {
        int totalDirArcs = g.edgeCount * 2;
        int[] arcFrom = new int[totalDirArcs];
        int[] arcTo = new int[totalDirArcs];
        float[] arcCost = new float[totalDirArcs];
        float[] arcLen = new float[totalDirArcs];
        int[] arcRawIdx = new int[totalDirArcs];
        byte[] arcIsRev = new byte[totalDirArcs];

        int arcIdx = 0;
        for (int u = 0; u < g.nodeCount; u++) {
            int sEdge = g.nodeOffsets[u];
            int eEdge = g.nodeOffsets[u + 1];
            for (int i = sEdge; i < eEdge; i++) {
                int v = g.edgesTarget[i];
                float cost = g.edgesCost[i];
                float revCost = g.edgesReverseCost[i];
                float edgeLen = (cost > 0) ? cost : ((revCost > 0) ? revCost : 100f);

                arcFrom[arcIdx] = u;
                arcTo[arcIdx] = v;
                arcCost[arcIdx] = cost;
                arcLen[arcIdx] = edgeLen;
                arcRawIdx[arcIdx] = i;
                arcIsRev[arcIdx] = 0;
                arcIdx++;

                arcFrom[arcIdx] = v;
                arcTo[arcIdx] = u;
                arcCost[arcIdx] = revCost;
                arcLen[arcIdx] = edgeLen;
                arcRawIdx[arcIdx] = i;
                arcIsRev[arcIdx] = 1;
                arcIdx++;
            }
        }

        Integer[] order = new Integer[totalDirArcs];
        for (int i = 0; i < totalDirArcs; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int cmp = Integer.compare(arcFrom[a], arcFrom[b]);
            return (cmp != 0) ? cmp : Integer.compare(arcTo[a], arcTo[b]);
        });

        g.dirNodeOffsets = new int[g.nodeCount + 1];
        g.dirTarget = new int[totalDirArcs];
        g.dirCost = new float[totalDirArcs];
        g.dirLen = new float[totalDirArcs];
        g.dirRawIdx = new int[totalDirArcs];
        g.dirIsRev = new byte[totalDirArcs];

        int currSrc = 0;
        for (int i = 0; i < totalDirArcs; i++) {
            int idx = order[i];
            int from = arcFrom[idx];
            while (currSrc < from) {
                currSrc++;
                g.dirNodeOffsets[currSrc] = i;
            }
            g.dirTarget[i] = arcTo[idx];
            g.dirCost[i] = arcCost[idx];
            g.dirLen[i] = arcLen[idx];
            g.dirRawIdx[i] = arcRawIdx[idx];
            g.dirIsRev[i] = arcIsRev[idx];
        }
        while (currSrc < g.nodeCount) {
            currSrc++;
            g.dirNodeOffsets[currSrc] = totalDirArcs;
        }
    }

    private static void buildSpatialGridIndex(PgrbGraph g) {
        int rangeLng = Math.max(1, g.maxLngS - g.minLngS);
        int rangeLat = Math.max(1, g.maxLatS - g.minLatS);
        g.cellWidthS = (double) rangeLng / g.gridCols;
        g.cellHeightS = (double) rangeLat / g.gridRows;

        int totalBuckets = g.gridCols * g.gridRows;
        List<Integer>[] tempBuckets = new List[totalBuckets];
        for (int i = 0; i < totalBuckets; i++) {
            tempBuckets[i] = new ArrayList<>();
        }

        for (int e = 0; e < g.edgeCount; e++) {
            int cStart = g.edgesCoordStart[e];
            int cEnd = (e + 1 < g.edgeCount) ? g.edgesCoordStart[e + 1] : g.pointCount;
            if (cEnd - cStart < 2) continue;

            int eMinLng = Integer.MAX_VALUE, eMaxLng = Integer.MIN_VALUE;
            int eMinLat = Integer.MAX_VALUE, eMaxLat = Integer.MIN_VALUE;

            for (int p = cStart; p < cEnd; p++) {
                int px = g.coordPool[p * 2];
                int py = g.coordPool[p * 2 + 1];
                if (px < eMinLng) eMinLng = px;
                if (px > eMaxLng) eMaxLng = px;
                if (py < eMinLat) eMinLat = py;
                if (py > eMaxLat) eMaxLat = py;
            }

            int minCol = (int) Math.floor((eMinLng - g.minLngS) / g.cellWidthS);
            int maxCol = (int) Math.floor((eMaxLng - g.minLngS) / g.cellWidthS);
            int minRow = (int) Math.floor((eMinLat - g.minLatS) / g.cellHeightS);
            int maxRow = (int) Math.floor((eMaxLat - g.minLatS) / g.cellHeightS);

            minCol = Math.max(0, Math.min(g.gridCols - 1, minCol));
            maxCol = Math.max(0, Math.min(g.gridCols - 1, maxCol));
            minRow = Math.max(0, Math.min(g.gridRows - 1, minRow));
            maxRow = Math.max(0, Math.min(g.gridRows - 1, maxRow));

            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    tempBuckets[r * g.gridCols + c].add(e);
                }
            }
        }

        g.spatialGridBuckets = new int[totalBuckets][];
        for (int b = 0; b < totalBuckets; b++) {
            List<Integer> list = tempBuckets[b];
            int[] arr = new int[list.size()];
            for (int k = 0; k < list.size(); k++) {
                arr[k] = list.get(k);
            }
            g.spatialGridBuckets[b] = arr;
        }
    }

    // ==========================================
    // 空间吸附算法 (snapToNearestEdge)
    // ==========================================

    public static PgrbSnap snapToNearestEdge(PgrbGraph g, double lng, double lat) {
        if (g == null || g.edgeCount == 0) return null;

        int lngS = (int) Math.round(lng * 1e6);
        int latS = (int) Math.round(lat * 1e6);

        int[] edgesToScan = null;
        if (g.spatialGridBuckets != null && g.cellWidthS > 0 && g.cellHeightS > 0) {
            int centerCol = (int) Math.floor((lngS - g.minLngS) / g.cellWidthS);
            int centerRow = (int) Math.floor((latS - g.minLatS) / g.cellHeightS);

            centerCol = Math.max(0, Math.min(g.gridCols - 1, centerCol));
            centerRow = Math.max(0, Math.min(g.gridRows - 1, centerRow));

            Set<Integer> edgeSet = new HashSet<>();
            int searchRadius = 3;

            for (int r = centerRow - searchRadius; r <= centerRow + searchRadius; r++) {
                if (r < 0 || r >= g.gridRows) continue;
                for (int c = centerCol - searchRadius; c <= centerCol + searchRadius; c++) {
                    if (c < 0 || c >= g.gridCols) continue;
                    int bIdx = r * g.gridCols + c;
                    int[] b = g.spatialGridBuckets[bIdx];
                    if (b != null) {
                        for (int e : b) edgeSet.add(e);
                    }
                }
            }
            if (!edgeSet.isEmpty()) {
                edgesToScan = new int[edgeSet.size()];
                int idx = 0;
                for (int e : edgeSet) edgesToScan[idx++] = e;
            }
        }

        double minDistSq = Double.POSITIVE_INFINITY;
        PgrbSnap bestSnap = null;

        int totalScan = (edgesToScan != null) ? edgesToScan.length : g.edgeCount;
        double radLat = Math.toRadians(lat);
        double cosLat = Math.cos(radLat);

        for (int idx = 0; idx < totalScan; idx++) {
            int e = (edgesToScan != null) ? edgesToScan[idx] : idx;
            int cStart = g.edgesCoordStart[e];
            int cEnd = (e + 1 < g.edgeCount) ? g.edgesCoordStart[e + 1] : g.pointCount;
            if (cEnd - cStart < 2) continue;

            float costVal = g.edgesCost[e];
            float revCostVal = g.edgesReverseCost[e];
            double edgeLenMeters = Math.max(0.1, costVal > 0 ? costVal : (revCostVal > 0 ? revCostVal : 100.0));

            for (int p = cStart; p < cEnd - 1; p++) {
                int ax = g.coordPool[p * 2];
                int ay = g.coordPool[p * 2 + 1];
                int bx = g.coordPool[(p + 1) * 2];
                int by = g.coordPool[(p + 1) * 2 + 1];

                double abx = (bx - ax) * cosLat;
                double aby = (by - ay);
                double apx = (lngS - ax) * cosLat;
                double apy = (latS - ay);

                double abLenSq = abx * abx + aby * aby;
                double t = 0;
                if (abLenSq > 0) {
                    t = (apx * abx + apy * aby) / abLenSq;
                    t = Math.max(0.0, Math.min(1.0, t));
                }

                double projX = ax + t * (bx - ax);
                double projY = ay + t * (by - ay);

                double dx = (lngS - projX) * cosLat;
                double dy = (latS - projY);
                double distSq = dx * dx + dy * dy;

                if (distSq < minDistSq) {
                    minDistSq = distSq;

                    int v = g.edgesTarget[e];
                    int u = 0;
                    int low = 0, high = g.nodeCount - 1;
                    while (low <= high) {
                        int mid = (low + high) >>> 1;
                        int sEdge = g.nodeOffsets[mid];
                        int eEdge = g.nodeOffsets[mid + 1];
                        if (e >= sEdge && e < eEdge) {
                            u = mid;
                            break;
                        } else if (e < sEdge) {
                            high = mid - 1;
                        } else {
                            low = mid + 1;
                        }
                    }

                    int totalPtsInEdge = cEnd - cStart;
                    double approxFrac = Math.max(0.0, Math.min(1.0, (p - cStart + t) / Math.max(1, totalPtsInEdge - 1)));

                    bestSnap = new PgrbSnap(
                            e,
                            p - cStart,
                            approxFrac,
                            new double[]{projX / 1e6, projY / 1e6},
                            u,
                            v,
                            edgeLenMeters,
                            (approxFrac > 0.5) ? v : u,
                            costVal,
                            revCostVal
                    );
                }
            }
        }

        return bestSnap;
    }

    // ==========================================
    // 高速 A* 寻径 (0-GC 二叉堆)
    // ==========================================

    private static class MinHeap {
        int[] nodes;
        float[] dists;
        int size = 0;

        MinHeap(int capacity) {
            nodes = new int[capacity];
            dists = new float[capacity];
        }

        boolean isEmpty() { return size == 0; }

        void push(int node, float dist) {
            if (size >= nodes.length) {
                int newCap = nodes.length * 2;
                nodes = Arrays.copyOf(nodes, newCap);
                dists = Arrays.copyOf(dists, newCap);
            }
            int i = size++;
            nodes[i] = node;
            dists[i] = dist;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (dists[i] >= dists[p]) break;
                int tn = nodes[i]; float td = dists[i];
                nodes[i] = nodes[p]; dists[i] = dists[p];
                nodes[p] = tn; dists[p] = td;
                i = p;
            }
        }

        int pop(float[] outDist) {
            if (size == 0) return -1;
            int topNode = nodes[0];
            outDist[0] = dists[0];
            size--;
            if (size > 0) {
                nodes[0] = nodes[size];
                dists[0] = dists[size];
                int i = 0;
                while (true) {
                    int smallest = i;
                    int left = (i << 1) + 1;
                    int right = left + 1;
                    if (left < size && dists[left] < dists[smallest]) smallest = left;
                    if (right < size && dists[right] < dists[smallest]) smallest = right;
                    if (smallest == i) break;
                    int tn = nodes[i]; float td = dists[i];
                    nodes[i] = nodes[smallest]; dists[i] = dists[smallest];
                    nodes[smallest] = tn; dists[smallest] = td;
                    i = smallest;
                }
            }
            return topNode;
        }
    }

    public static int[] astar(PgrbGraph g, int startIdx, int endIdx, boolean directed, double[] outDistance) {
        if (g == null || startIdx < 0 || endIdx < 0 || startIdx >= g.nodeCount || endIdx >= g.nodeCount) {
            return new int[0];
        }
        if (startIdx == endIdx) {
            outDistance[0] = 0;
            return new int[]{startIdx};
        }

        float[] dist = new float[g.nodeCount];
        int[] prev = new int[g.nodeCount];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[startIdx] = 0;

        int endCoordIdx = g.nodeFirstCoordIdx[endIdx];
        int endLngS = endCoordIdx >= 0 ? g.coordPool[endCoordIdx * 2] : 0;
        int endLatS = endCoordIdx >= 0 ? g.coordPool[endCoordIdx * 2 + 1] : 0;

        MinHeap pq = new MinHeap(Math.min(g.nodeCount, 65536));
        pq.push(startIdx, computeHeuristic(g, startIdx, endLngS, endLatS));

        float[] popDist = new float[1];
        while (!pq.isEmpty()) {
            int u = pq.pop(popDist);
            if (u == endIdx) break;

            int edgeStart = (g.dirNodeOffsets != null) ? g.dirNodeOffsets[u] : g.nodeOffsets[u];
            int edgeEnd = (g.dirNodeOffsets != null) ? g.dirNodeOffsets[u + 1] : g.nodeOffsets[u + 1];

            for (int i = edgeStart; i < edgeEnd; i++) {
                int target = (g.dirTarget != null) ? g.dirTarget[i] : g.edgesTarget[i];
                float cost = directed
                        ? (g.dirCost != null ? g.dirCost[i] : g.edgesCost[i])
                        : (g.dirLen != null ? g.dirLen[i] : (g.edgesCost[i] > 0 ? g.edgesCost[i] : 100f));

                if (cost >= 0) {
                    float alt = dist[u] + cost;
                    if (alt < dist[target]) {
                        dist[target] = alt;
                        prev[target] = u;
                        pq.push(target, alt + computeHeuristic(g, target, endLngS, endLatS));
                    }
                }
            }
        }

        if (Float.isInfinite(dist[endIdx])) {
            outDistance[0] = 0;
            return new int[0];
        }

        List<Integer> pathList = new ArrayList<>();
        for (int curr = endIdx; curr != -1; curr = prev[curr]) {
            pathList.add(curr);
        }
        Collections.reverse(pathList);

        int[] path = new int[pathList.size()];
        for (int i = 0; i < pathList.size(); i++) {
            path[i] = pathList.get(i);
        }
        outDistance[0] = dist[endIdx];
        return path;
    }

    private static float computeHeuristic(PgrbGraph g, int u, int endLngS, int endLatS) {
        int cIdx = g.nodeFirstCoordIdx[u];
        if (cIdx < 0) return 0f;
        double dx = (g.coordPool[cIdx * 2] - endLngS) * 0.09;
        double dy = (g.coordPool[cIdx * 2 + 1] - endLatS) * 0.11;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ==========================================
    // 规划路径并直接组装 PgrbRoutePath 领域模型
    // ==========================================

    public static PgrbRoutePath planRouteWithSnap(PgrbGraph g, double startLng, double startLat, double endLng, double endLat, boolean directed) {
        if (g == null) {
            return PgrbRoutePath.error(400, "路网尚未加载");
        }

        PgrbSnap startSnap = snapToNearestEdge(g, startLng, startLat);
        PgrbSnap endSnap = snapToNearestEdge(g, endLng, endLat);

        if (startSnap == null || endSnap == null) {
            return PgrbRoutePath.notFound("起终点无法吸附到有效路网");
        }

        // 0. 特殊处理：起终点在同一条边
        if (startSnap.edgeIdx == endSnap.edgeIdx) {
            List<int[]> sameCoords = getSameEdgeCoordsScaled(g, startSnap, endSnap, directed);
            if (sameCoords != null && sameCoords.size() >= 2) {
                double dist = Math.abs(startSnap.t - endSnap.t) * startSnap.length;
                return buildRoutePath(g, new int[]{startSnap.u, startSnap.v}, dist, sameCoords);
            }
        }

        // 构建起点候选节点
        List<Candidate> startCands = new ArrayList<>();
        if (!directed || startSnap.revCost >= 0) {
            startCands.add(new Candidate(startSnap.u, startSnap.t * startSnap.length));
        }
        if (!directed || startSnap.cost >= 0) {
            startCands.add(new Candidate(startSnap.v, (1.0 - startSnap.t) * startSnap.length));
        }
        if (startCands.isEmpty()) {
            return PgrbRoutePath.error(400, "起点所在道路受单向通行限制，无合法出行方向");
        }

        // 构建终点候选节点
        List<Candidate> endCands = new ArrayList<>();
        if (!directed || endSnap.cost >= 0) {
            endCands.add(new Candidate(endSnap.u, endSnap.t * endSnap.length));
        }
        if (!directed || endSnap.revCost >= 0) {
            endCands.add(new Candidate(endSnap.v, (1.0 - endSnap.t) * endSnap.length));
        }
        if (endCands.isEmpty()) {
            return PgrbRoutePath.error(400, "终点所在道路受单向通行限制，无合法驶入方向");
        }

        int[] bestPath = null;
        double minTotalDist = Double.POSITIVE_INFINITY;
        double[] outDist = new double[1];

        for (Candidate sCand : startCands) {
            for (Candidate eCand : endCands) {
                int[] path = astar(g, sCand.node, eCand.node, directed, outDist);
                if (path != null && path.length > 0) {
                    double total = sCand.partialCost + outDist[0] + eCand.partialCost;
                    if (total < minTotalDist) {
                        minTotalDist = total;
                        bestPath = path;
                    }
                }
            }
        }

        if (bestPath == null || bestPath.length == 0) {
            return PgrbRoutePath.notFound("起点与终点之间未找到连通路径");
        }

        // 组装定点折线集合
        List<int[]> finalCoordsScaled = assembleGeometryScaled(g, bestPath, startSnap, endSnap);
        return buildRoutePath(g, bestPath, minTotalDist, finalCoordsScaled);
    }

    private static class Candidate {
        int node;
        double partialCost;
        Candidate(int node, double partialCost) {
            this.node = node;
            this.partialCost = partialCost;
        }
    }

    private static List<int[]> assembleGeometryScaled(PgrbGraph g, int[] path, PgrbSnap startSnap, PgrbSnap endSnap) {
        List<int[]> finalCoords = new ArrayList<>();
        int kStart = 0;
        int kEnd = path.length - 1;

        if (startSnap != null && path.length >= 2) {
            int firstEdgeIdx = findEdgeIdxBetween(g, path[0], path[1]);
            if (firstEdgeIdx == startSnap.edgeIdx) {
                addCoordsUniqueScaled(finalCoords, getStartSegCoordsScaled(g, startSnap, path[1]));
                kStart = 1;
            } else {
                addCoordsUniqueScaled(finalCoords, getStartSegCoordsScaled(g, startSnap, path[0]));
            }
        } else if (startSnap != null && path.length == 1) {
            addCoordsUniqueScaled(finalCoords, getStartSegCoordsScaled(g, startSnap, path[0]));
        }

        List<int[]> customEndSeg = null;
        if (endSnap != null && path.length >= 2) {
            int lastEdgeIdx = findEdgeIdxBetween(g, path[path.length - 2], path[path.length - 1]);
            if (lastEdgeIdx == endSnap.edgeIdx) {
                customEndSeg = getEndSegCoordsScaled(g, endSnap, path[path.length - 2]);
                kEnd = path.length - 2;
            } else {
                customEndSeg = getEndSegCoordsScaled(g, endSnap, path[path.length - 1]);
            }
        } else if (endSnap != null && path.length == 1) {
            customEndSeg = getEndSegCoordsScaled(g, endSnap, path[0]);
        }

        for (int k = kStart; k < kEnd; k++) {
            int u = path[k];
            int v = path[k + 1];
            int matchedEdgeIdx = -1;
            boolean isReverse = false;

            int sEdgeU = g.nodeOffsets[u];
            int eEdgeU = g.nodeOffsets[u + 1];
            for (int i = sEdgeU; i < eEdgeU; i++) {
                if (g.edgesTarget[i] == v) {
                    matchedEdgeIdx = i;
                    isReverse = false;
                    break;
                }
            }
            if (matchedEdgeIdx == -1) {
                int sEdgeV = g.nodeOffsets[v];
                int eEdgeV = g.nodeOffsets[v + 1];
                for (int j = sEdgeV; j < eEdgeV; j++) {
                    if (g.edgesTarget[j] == u) {
                        matchedEdgeIdx = j;
                        isReverse = true;
                        break;
                    }
                }
            }

            if (matchedEdgeIdx != -1) {
                List<int[]> pts = getEdgeCoordsScaled(g, matchedEdgeIdx);
                if (isReverse) Collections.reverse(pts);
                addCoordsUniqueScaled(finalCoords, pts);
            }
        }

        if (customEndSeg != null) {
            addCoordsUniqueScaled(finalCoords, customEndSeg);
        }

        return finalCoords;
    }

    private static void addCoordsUniqueScaled(List<int[]> dest, List<int[]> src) {
        if (src == null) return;
        for (int[] pt : src) {
            if (dest.isEmpty()) {
                dest.add(pt);
            } else {
                int[] last = dest.get(dest.size() - 1);
                if (last[0] != pt[0] || last[1] != pt[1]) {
                    dest.add(pt);
                }
            }
        }
    }

    private static List<int[]> getEdgeCoordsScaled(PgrbGraph g, int edgeIdx) {
        List<int[]> res = new ArrayList<>();
        if (edgeIdx < 0 || edgeIdx >= g.edgeCount) return res;
        int cStart = g.edgesCoordStart[edgeIdx];
        int cEnd = (edgeIdx + 1 < g.edgeCount) ? g.edgesCoordStart[edgeIdx + 1] : g.pointCount;
        for (int p = cStart; p < cEnd; p++) {
            res.add(new int[]{g.coordPool[p * 2], g.coordPool[p * 2 + 1]});
        }
        return res;
    }

    private static List<int[]> getStartSegCoordsScaled(PgrbGraph g, PgrbSnap startSnap, int targetNode) {
        if (startSnap == null) return Collections.emptyList();
        List<int[]> pts = getEdgeCoordsScaled(g, startSnap.edgeIdx);
        int[] projScaled = new int[]{(int) Math.round(startSnap.projPoint[0] * 1e6), (int) Math.round(startSnap.projPoint[1] * 1e6)};
        if (pts.size() < 2) return Collections.singletonList(projScaled);

        int segIdx = Math.min(startSnap.segIdx, pts.size() - 2);
        List<int[]> res = new ArrayList<>();
        res.add(projScaled);

        if (targetNode == startSnap.u) {
            for (int i = segIdx; i >= 0; i--) {
                int[] pt = pts.get(i);
                if (pt[0] != projScaled[0] || pt[1] != projScaled[1]) {
                    res.add(pt);
                }
            }
        } else {
            for (int i = segIdx + 1; i < pts.size(); i++) {
                int[] pt = pts.get(i);
                if (pt[0] != projScaled[0] || pt[1] != projScaled[1]) {
                    res.add(pt);
                }
            }
        }
        return res;
    }

    private static List<int[]> getEndSegCoordsScaled(PgrbGraph g, PgrbSnap endSnap, int fromNode) {
        if (endSnap == null) return Collections.emptyList();
        List<int[]> pts = getEdgeCoordsScaled(g, endSnap.edgeIdx);
        int[] projScaled = new int[]{(int) Math.round(endSnap.projPoint[0] * 1e6), (int) Math.round(endSnap.projPoint[1] * 1e6)};
        if (pts.size() < 2) return Collections.singletonList(projScaled);

        int segIdx = Math.min(endSnap.segIdx, pts.size() - 2);
        List<int[]> res = new ArrayList<>();

        if (fromNode == endSnap.u) {
            for (int i = 0; i <= segIdx; i++) {
                res.add(pts.get(i));
            }
            if (pts.get(segIdx)[0] != projScaled[0] || pts.get(segIdx)[1] != projScaled[1]) {
                res.add(projScaled);
            }
        } else {
            for (int i = pts.size() - 1; i >= segIdx + 1; i--) {
                res.add(pts.get(i));
            }
            if (pts.get(segIdx + 1)[0] != projScaled[0] || pts.get(segIdx + 1)[1] != projScaled[1]) {
                res.add(projScaled);
            }
        }
        return res;
    }

    private static List<int[]> getSameEdgeCoordsScaled(PgrbGraph g, PgrbSnap startSnap, PgrbSnap endSnap, boolean directed) {
        if (startSnap == null || endSnap == null || startSnap.edgeIdx != endSnap.edgeIdx) return null;

        List<int[]> pts = getEdgeCoordsScaled(g, startSnap.edgeIdx);
        int[] p1 = new int[]{(int) Math.round(startSnap.projPoint[0] * 1e6), (int) Math.round(startSnap.projPoint[1] * 1e6)};
        int[] p2 = new int[]{(int) Math.round(endSnap.projPoint[0] * 1e6), (int) Math.round(endSnap.projPoint[1] * 1e6)};
        if (pts.size() < 2) {
            return Arrays.asList(p1, p2);
        }

        boolean isForward = (startSnap.segIdx < endSnap.segIdx) ||
                (startSnap.segIdx == endSnap.segIdx && startSnap.t <= endSnap.t);

        if (directed) {
            if (isForward && startSnap.cost < 0) return null;
            if (!isForward && startSnap.revCost < 0) return null;
        }

        int segIdx1 = Math.min(startSnap.segIdx, pts.size() - 2);
        int segIdx2 = Math.min(endSnap.segIdx, pts.size() - 2);

        List<int[]> res = new ArrayList<>();
        res.add(p1);

        if (segIdx1 == segIdx2) {
            if (p1[0] != p2[0] || p1[1] != p2[1]) {
                res.add(p2);
            }
        } else if (isForward) {
            for (int i = segIdx1 + 1; i <= segIdx2; i++) {
                int[] pt = pts.get(i);
                int[] last = res.get(res.size() - 1);
                if (last[0] != pt[0] || last[1] != pt[1]) res.add(pt);
            }
            int[] last = res.get(res.size() - 1);
            if (last[0] != p2[0] || last[1] != p2[1]) res.add(p2);
        } else {
            for (int i = segIdx1; i >= segIdx2 + 1; i--) {
                int[] pt = pts.get(i);
                int[] last = res.get(res.size() - 1);
                if (last[0] != pt[0] || last[1] != pt[1]) res.add(pt);
            }
            int[] last = res.get(res.size() - 1);
            if (last[0] != p2[0] || last[1] != p2[1]) res.add(p2);
        }
        return res;
    }

    private static int findEdgeIdxBetween(PgrbGraph g, int u, int v) {
        if (u < 0 || v < 0 || g.nodeOffsets == null) return -1;
        int sU = g.nodeOffsets[u], eU = g.nodeOffsets[u + 1];
        for (int i = sU; i < eU; i++) {
            if (g.edgesTarget[i] == v) return i;
        }
        int sV = g.nodeOffsets[v], eV = g.nodeOffsets[v + 1];
        for (int j = sV; j < eV; j++) {
            if (g.edgesTarget[j] == u) return j;
        }
        return -1;
    }

    private static PgrbRoutePath buildRoutePath(PgrbGraph g, int[] path, double distance, List<int[]> coordsScaled) {
        PgrbRoutePath rp = new PgrbRoutePath();
        rp.code = 200;
        rp.message = "success";
        rp.networkId = g.networkId;
        rp.totalDistance = distance;
        rp.nodeCount = path.length;
        rp.pathNodes = path;
        rp.startNodeId = g.idMap[path[0]];
        rp.endNodeId = g.idMap[path[path.length - 1]];
        rp.coordCount = coordsScaled.size();

        int[] flatCoords = new int[coordsScaled.size() * 2];
        for (int i = 0; i < coordsScaled.size(); i++) {
            int[] pt = coordsScaled.get(i);
            flatCoords[i * 2] = pt[0];
            flatCoords[i * 2 + 1] = pt[1];
        }
        rp.coords = flatCoords;
        rp.toBinary(); // 预热生成 cachedBinary
        return rp;
    }
}
