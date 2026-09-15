package com.qskj.get_geo_pg.util.pgrb;

import com.qskj.get_geo_pg.pojo.PgrbBoundaryBinary;

import java.io.Serializable;

/**
 * PGRB 内存拓扑图模型 (纯净 0-GC 扁平原生数组模型)
 */
public class PgrbGraph implements Serializable {
    private static final long serialVersionUID = 1L;

    public String networkId;
    public int version = 1;
    public int nodeCount;
    public int edgeCount;
    public int pointCount;

    public double minLng;
    public double minLat;
    public double maxLng;
    public double maxLat;

    public int minLngS;
    public int minLatS;
    public int maxLngS;
    public int maxLatS;

    // 前向星结构 (紧凑连续原生数组)
    public int[] nodeOffsets;      // [N + 1]
    public int[] edgesTarget;      // [M]
    public float[] edgesCost;      // [M]
    public float[] edgesReverseCost;// [M]
    public int[] edgesCoordStart;  // [M]
    public int[] coordPool;        // [P * 2] (lngScaled, latScaled)
    public long[] idMap;           // [N] (原始 PostGIS 节点 ID)

    // 节点首坐标索引查找表
    public int[] nodeFirstCoordIdx;// [N]

    // 全有向图前向星结构 (含正反向双向弧段)
    public int[] dirNodeOffsets;   // [N + 1]
    public int[] dirTarget;        // [2 * M]
    public float[] dirCost;        // [2 * M]
    public float[] dirLen;         // [2 * M]
    public int[] dirRawIdx;        // [2 * M]
    public byte[] dirIsRev;        // [2 * M]

    // 128x128 空间网格桶索引
    public int gridCols = 128;
    public int gridRows = 128;
    public double cellWidthS = 1;
    public double cellHeightS = 1;
    public int[][] spatialGridBuckets; // [128 * 128][edges]

    // 组合持有的独立空间边界对象
    public PgrbBoundaryBinary boundary;
}
