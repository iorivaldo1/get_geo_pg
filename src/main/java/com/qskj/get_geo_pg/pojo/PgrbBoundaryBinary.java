package com.qskj.get_geo_pg.pojo;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * PGRB 行政区边界几何与二进制协议模型 (PGBB 协议)
 * 采用小端序紧凑定点整数存储 (经纬度放大 1e6 倍)
 */
public class PgrbBoundaryBinary implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String MAGIC = "PGBB";
    public static final short CURRENT_VERSION = 1;

    public String networkId;
    public int ringCount;         // 环数 (单外环为1，飞地为N)
    public int totalPointCount;   // 顶点总数 P
    public int[] ringSizes;       // 各环顶点数数组 [ringCount]
    public int[] coords;          // 定点整数经纬度 [P * 2] (lngScaled, latScaled)
    public byte[] cachedBinary;   // 缓存好的二进制字节流

    public PgrbBoundaryBinary() {}

    public PgrbBoundaryBinary(String networkId, int ringCount, int totalPointCount, int[] ringSizes, int[] coords) {
        this.networkId = networkId;
        this.ringCount = ringCount;
        this.totalPointCount = totalPointCount;
        this.ringSizes = ringSizes;
        this.coords = coords;
    }

    /**
     * 将边界数据打包成符合 PGBB 协议规范的紧凑二进制字节流
     */
    public synchronized byte[] toBinary() {
        if (cachedBinary != null) {
            return cachedBinary;
        }

        // Header: Magic(4B) + Version(2B) + Flags(2B) + RingCount(4B) + TotalPoints(4B) = 16B
        // Body: ringSizes(ringCount * 4B) + coords(totalPointCount * 8B)
        int totalBytes = 16 + (ringCount * 4) + (totalPointCount * 8);
        ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);

        buf.put(MAGIC.getBytes());
        buf.putShort(CURRENT_VERSION);
        buf.putShort((short) 0); // flags
        buf.putInt(ringCount);
        buf.putInt(totalPointCount);

        for (int r = 0; r < ringCount; r++) {
            buf.putInt(ringSizes[r]);
        }

        for (int i = 0; i < totalPointCount * 2; i++) {
            buf.putInt(coords[i]);
        }

        this.cachedBinary = buf.array();
        return this.cachedBinary;
    }

    /**
     * 内聚射线法：快速判断经纬度坐标 (lng, lat) 是否落在当前行政区多边形边界内
     */
    public boolean contains(double lng, double lat) {
        if (coords == null || totalPointCount == 0 || ringSizes == null) {
            return true;
        }
        int targetX = (int) Math.round(lng * 1e6);
        int targetY = (int) Math.round(lat * 1e6);

        int ptOffset = 0;
        for (int r = 0; r < ringCount; r++) {
            int rSize = ringSizes[r];
            if (isPointInRing(targetX, targetY, coords, ptOffset, rSize)) {
                return true;
            }
            ptOffset += rSize;
        }
        return false;
    }

    private static boolean isPointInRing(int x, int y, int[] coords, int offset, int size) {
        boolean inside = false;
        for (int i = 0, j = size - 1; i < size; j = i++) {
            int xi = coords[(offset + i) * 2];
            int yi = coords[(offset + i) * 2 + 1];
            int xj = coords[(offset + j) * 2];
            int yj = coords[(offset + j) * 2 + 1];

            boolean intersect = ((yi > y) != (yj > y)) && (x < (double) (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /**
     * 针对历史 v1 无多边形路网的保底构建：用 BBOX 4 顶点构建单环矩形边界
     */
    public static PgrbBoundaryBinary fromBBox(String networkId, int minLngS, int minLatS, int maxLngS, int maxLatS) {
        PgrbBoundaryBinary b = new PgrbBoundaryBinary();
        b.networkId = networkId;
        b.ringCount = 1;
        b.totalPointCount = 4;
        b.ringSizes = new int[]{4};
        b.coords = new int[]{
                minLngS, minLatS,
                maxLngS, minLatS,
                maxLngS, maxLatS,
                minLngS, maxLatS
        };
        b.toBinary();
        return b;
    }

    /**
     * 从 PGRB 文件流中解构边界段 (自适应支持 v3 纯坐标池与 v2 复合结构)
     */
    public static PgrbBoundaryBinary parseFromPgrbBuffer(String networkId, int version, ByteBuffer buf, int bufLength, int boundaryOffset) {
        if (bufLength < boundaryOffset + 8) {
            return null;
        }

        if (version >= 3) {
            int remainingBytes = bufLength - boundaryOffset;
            int totalPts = remainingBytes / 8;
            if (totalPts <= 0) {
                return null;
            }

            buf.position(boundaryOffset);
            int[] coordinateArray = new int[totalPts * 2];
            for (int i = 0; i < totalPts * 2; i++) {
                coordinateArray[i] = buf.getInt();
            }

            int[] rSizes = new int[]{totalPts};
            PgrbBoundaryBinary boundary = new PgrbBoundaryBinary(networkId, 1, totalPts, rSizes, coordinateArray);
            boundary.toBinary(); // 预热生成 cachedBinary
            return boundary;
        } else {
            buf.position(boundaryOffset);
            int totalPts = buf.getInt();
            int rCount = buf.getInt();
            if (totalPts <= 0 || rCount <= 0) {
                return null;
            }

            int[] rSizes = new int[rCount];
            for (int r = 0; r < rCount; r++) {
                rSizes[r] = buf.getInt();
            }

            int[] coordinateArray = new int[totalPts * 2];
            for (int i = 0; i < totalPts * 2; i++) {
                coordinateArray[i] = buf.getInt();
            }

            PgrbBoundaryBinary boundary = new PgrbBoundaryBinary(networkId, rCount, totalPts, rSizes, coordinateArray);
            boundary.toBinary(); // 预热生成 cachedBinary
            return boundary;
        }
    }

    /**
     * 向后兼容重载方法 (默认按 v2 格式解析)
     */
    public static PgrbBoundaryBinary parseFromPgrbBuffer(String networkId, ByteBuffer buf, int bufLength, int boundaryOffset) {
        return parseFromPgrbBuffer(networkId, 2, buf, bufLength, boundaryOffset);
    }
}
