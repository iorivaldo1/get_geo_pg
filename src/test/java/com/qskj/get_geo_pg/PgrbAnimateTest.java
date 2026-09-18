package com.qskj.get_geo_pg;

import com.qskj.get_geo_pg.util.pgrb.PgrbGraph;
import com.qskj.get_geo_pg.util.pgrb.PgrbGraphEngine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PgrbAnimateTest {

    @Test
    public void testAStarAnimateOnExample2() throws Exception {
        File file = new File("e:/JavaPro/QSKJ/get_geo_pg/data/pgrb/example_2_road.pgrb");
        if (!file.exists()) {
            file = new File("data/pgrb/example_2_road.pgrb");
        }
        assertTrue(file.exists(), "example_2_road.pgrb must exist");

        byte[] bytes = Files.readAllBytes(file.toPath());
        PgrbGraph g = PgrbGraphEngine.loadFromBytes("example_2_road", bytes);
        assertNotNull(g);
        assertEquals(51, g.nodeCount);
        assertEquals(60, g.edgeCount);

        // Coordinates from route_astar_example_2_v3.md
        double startLng = 104.095341;
        double startLat = 30.634007;
        double endLng = 104.096135;
        double endLat = 30.631912;

        Map<String, Object> result = PgrbGraphEngine.planRouteWithSnapAnimate(g, startLng, startLat, endLng, endLat,
                true, 2000);
        assertNotNull(result);
        assertEquals(200, result.get("code"));

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertNotNull(data);

        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertNotNull(steps);
        assertFalse(steps.isEmpty(), "steps should not be empty");

        boolean hasPush = false;
        boolean hasPop = false;
        for (Map<String, Object> s : steps) {
            String act = (String) s.get("action");
            if ("PUSH".equals(act))
                hasPush = true;
            if ("POP".equals(act))
                hasPop = true;
            assertNotNull(s.get("coord"));
            assertNotNull(s.get("cost"));
        }
        assertTrue(hasPush, "Must contain PUSH step");
        assertTrue(hasPop, "Must contain POP step");

        Map<String, Object> finalPath = (Map<String, Object>) data.get("finalPath");
        assertNotNull(finalPath);
        assertTrue((double) finalPath.get("totalDistance") > 0);
        List<List<Double>> coords = (List<List<Double>>) finalPath.get("coordinates");
        assertNotNull(coords);
        assertTrue(coords.size() >= 2);

        for (Map<String, Object> s : steps) {
            List<?> b = (List<?>) s.get("branches");
            System.out.println("STEP " + s.get("step") + ": " + s.get("action") + " node=" + s.get("node")
                    + " branches=" + (b != null ? b.size() : 0));
        }

        System.out.println("AStarAnimateTest passed! Steps count: " + steps.size()
                + ", distance: " + finalPath.get("totalDistance")
                + "m, path coords: " + coords.size());
    }
}
