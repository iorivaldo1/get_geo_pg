package com.qskj.get_geo_pg;

import com.qskj.get_geo_pg.pojo.PgrbBoundaryBinary;
import com.qskj.get_geo_pg.util.pgrb.PgrbGraph;
import com.qskj.get_geo_pg.util.pgrb.PgrbGraphEngine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class PgrbBoundaryTest {

    @Test
    void testMultiRingBoundaryParsing() throws Exception {
        File pgrbFile = new File("E:/JavaPro/QSKJ/get_geo_pg/data/pgrb/xzq_village_510106018013_3d.pgrb");
        assertTrue(pgrbFile.exists(), "xzq_village_510106018013_3d.pgrb should exist");

        byte[] bytes = Files.readAllBytes(pgrbFile.toPath());
        PgrbGraph graph = PgrbGraphEngine.loadFromBytes("xzq_village_510106018013_3d", bytes);

        assertNotNull(graph, "Graph should load successfully");
        assertNotNull(graph.boundary, "Boundary should be parsed");

        System.out.println("=== TEST RESULT ===");
        System.out.println("Network: " + graph.networkId);
        System.out.println("Ring count: " + graph.boundary.ringCount);
        System.out.println("Total points: " + graph.boundary.totalPointCount);
        System.out.println("Ring sizes: " + java.util.Arrays.toString(graph.boundary.ringSizes));

        assertEquals(2, graph.boundary.ringCount, "Ring count must be 2 for multi-ring village");
        assertEquals(671, graph.boundary.totalPointCount, "Total points must be 671");
        assertArrayEquals(new int[]{629, 42}, graph.boundary.ringSizes, "Ring sizes must be [629, 42]");

        // Test boundary binary output (unified 8B header: ringCount + totalPointCount)
        byte[] boundaryBytes = graph.boundary.toBinary();
        assertNotNull(boundaryBytes);
        assertTrue(boundaryBytes.length >= 8);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(boundaryBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, bb.getInt());
        assertEquals(671, bb.getInt());

        System.out.println("Boundary binary size: " + boundaryBytes.length + " bytes (8B header + ringSizes + coords)");
        System.out.println("=== TEST 1 (xzq_village_510106018013_3d) PASSED ===");

        // Test town with 1 ring
        File townFile = new File("E:/JavaPro/QSKJ/get_geo_pg/data/pgrb/xzq_town_510104023_3d.pgrb");
        if (townFile.exists()) {
            byte[] townBytes = Files.readAllBytes(townFile.toPath());
            PgrbGraph townGraph = PgrbGraphEngine.loadFromBytes("xzq_town_510104023_3d", townBytes);

            assertNotNull(townGraph);
            assertNotNull(townGraph.boundary);
            assertEquals(1, townGraph.boundary.ringCount, "Town ring count must be 1");
            assertEquals(133, townGraph.boundary.totalPointCount, "Town total points must be 133");
            System.out.println("=== TEST 2 (xzq_town_510104023_3d) PASSED ===");
        }
    }
}
