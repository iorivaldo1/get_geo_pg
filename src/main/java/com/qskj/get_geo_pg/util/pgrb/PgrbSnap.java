package com.qskj.get_geo_pg.util.pgrb;

public class PgrbSnap {
    public int edgeIdx;
    public int segIdx;
    public double t;
    public double[] projPoint; // [lng, lat]
    public int u;
    public int v;
    public double length;
    public int bestNode;
    public float cost;
    public float revCost;

    public PgrbSnap() {}

    public PgrbSnap(int edgeIdx, int segIdx, double t, double[] projPoint, int u, int v, double length, int bestNode, float cost, float revCost) {
        this.edgeIdx = edgeIdx;
        this.segIdx = segIdx;
        this.t = t;
        this.projPoint = projPoint;
        this.u = u;
        this.v = v;
        this.length = length;
        this.bestNode = bestNode;
        this.cost = cost;
        this.revCost = revCost;
    }
}
