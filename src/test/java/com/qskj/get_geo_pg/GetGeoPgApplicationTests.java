package com.qskj.get_geo_pg;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GetGeoPgApplicationTests {

    @Test
    void contextLoads() throws Exception {
        String url = "jdbc:postgresql://localhost:5432/mdb1";
        String user = "postgres";
        String password = "4732";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, user, password);
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema='excalidraw' AND table_name='excalidraw_json'")) {
            System.out.println("SCHEMA_START");
            while (rs.next()) {
                System.out.printf("SCHEMA_COL: %s, %s%n", rs.getString("column_name"), rs.getString("data_type"));
            }
            System.out.println("SCHEMA_END");
        }
    }

}
