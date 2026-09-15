package com.qskj.get_geo_pg;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;

public class JdbcTest {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/mdb1";
        String user = "postgres";
        String password = "4732";
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getColumns(null, null, "rivers", null);
            while (rs.next()) {
                System.out.println("COLUMN_NAME: " + rs.getString("COLUMN_NAME"));
            }
        }
    }
}
