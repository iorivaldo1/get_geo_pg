package com.qskj.get_geo_pg.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.ByteBuffer;

@RestController
@RequestMapping("/shp_record")
@CrossOrigin
public class ShpController {

    @GetMapping("/{shapeId}")
    public ResponseEntity<byte[]> getShpRecord(@PathVariable int shapeId) {
        try {
            // Read SHX to get offset and length
            ClassPathResource shxResource = new ClassPathResource("shp/ya_river.shx");
            byte[] shxHeader = new byte[8];
            int shxOffset = 100 + shapeId * 8;
            
            int offsetWord = 0;
            int contentLengthWord = 0;
            try (InputStream shxIs = shxResource.getInputStream()) {
                long skipped = shxIs.skip(shxOffset);
                if (skipped != shxOffset) {
                    // Try skipping multiple times if needed, or loop
                    long remaining = shxOffset - skipped;
                    while (remaining > 0) {
                        long s = shxIs.skip(remaining);
                        if (s == 0) {
                            if (shxIs.read() == -1) break; // EOF
                            remaining--;
                        } else {
                            remaining -= s;
                        }
                    }
                }
                
                int read = 0;
                while (read < 8) {
                    int r = shxIs.read(shxHeader, read, 8 - read);
                    if (r == -1) break;
                    read += r;
                }
                
                ByteBuffer buffer = ByteBuffer.wrap(shxHeader);
                offsetWord = buffer.getInt(); // big-endian
                contentLengthWord = buffer.getInt(); // big-endian
            }

            int startByte = offsetWord * 2;
            int lengthByte = 8 + contentLengthWord * 2;

            // Read SHP chunk
            ClassPathResource shpResource = new ClassPathResource("shp/ya_river.shp");
            byte[] shpContent = new byte[lengthByte];
            try (InputStream shpIs = shpResource.getInputStream()) {
                long remainingSkip = startByte;
                while (remainingSkip > 0) {
                    long s = shpIs.skip(remainingSkip);
                    if (s == 0) {
                        if (shpIs.read() == -1) break; // EOF
                        remainingSkip--;
                    } else {
                        remainingSkip -= s;
                    }
                }

                int read = 0;
                while (read < lengthByte) {
                    int r = shpIs.read(shpContent, read, lengthByte - read);
                    if (r == -1) break;
                    read += r;
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return new ResponseEntity<>(shpContent, headers, HttpStatus.OK);

        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
