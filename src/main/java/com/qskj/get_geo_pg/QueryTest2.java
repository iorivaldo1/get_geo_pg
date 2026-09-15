package com.qskj.get_geo_pg;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class QueryTest2 {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/mdb1";
        String user = "postgres";
        String password = "4732";
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("WITH RECURSIVE river_path AS (" +
                     "    SELECT * FROM (" +
                     "        SELECT gid, rivid, name, flowriv, flowid, geom, " +
                     "               ST_LineSubstring(ST_GeometryN(geom, 1), ST_LineLocatePoint(ST_GeometryN(geom, 1), ST_MakePoint(102.92868, 30.03797)), 1) as geom_part, " +
                     "               ARRAY[rivid] as path " +
                     "        FROM ya_data.rivers " +
                     "        ORDER BY geom <-> ST_MakePoint(102.92868, 30.03797) " +
                     "        LIMIT 1" +
                     "    ) AS base_case " +
                     "    UNION ALL " +
                     "    SELECT r.gid, r.rivid, r.name, r.flowriv, r.flowid, r.geom, " +
                     "           ST_LineSubstring(ST_GeometryN(r.geom, 1), ST_LineLocatePoint(ST_GeometryN(r.geom, 1), ST_ClosestPoint(ST_GeometryN(r.geom, 1), ST_GeometryN(rp.geom, 1))), 1) as geom_part, " +
                     "           rp.path || r.rivid " +
                     "    FROM ya_data.rivers r " +
                     "    INNER JOIN river_path rp ON r.rivid = rp.flowid " +
                     "    WHERE r.rivid <> ALL(rp.path) " +
                     ") " +
                     "SELECT gid, rivid, ST_AsText(geom_part) as geometry " +
                     "FROM river_path")) {
            while (rs.next()) {
                System.out.printf("gid: %s, rivid: %s, geom_part: %s%n",
                        rs.getString("gid"), rs.getString("rivid"),
                        rs.getString("geometry") != null ? rs.getString("geometry").substring(0, Math.min(rs.getString("geometry").length(), 50)) + "..." : "null");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
