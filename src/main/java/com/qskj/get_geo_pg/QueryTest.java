package com.qskj.get_geo_pg;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class QueryTest {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/mdb1";
        String user = "postgres";
        String password = "4732";
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema='excalidraw' AND table_name='excalidraw_json'")) {
            while (rs.next()) {
                System.out.printf("column_name: %s, data_type: %s%n", rs.getString("column_name"), rs.getString("data_type"));
            }
        }
    }
}
