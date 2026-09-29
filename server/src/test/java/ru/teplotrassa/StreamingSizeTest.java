package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.teplotrassa.data.GeoJsonInput;

/** Opt-in real 3 GiB parse. Padding isolates transport/parser memory from feature density. */
@Tag("large")
class StreamingSizeTest {
  @TempDir Path temp;

  @Test
  void parsesThreeGiBWithoutWholeFileAllocation() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("largeFiles"));
    Path file = temp.resolve("three-gib.geojson");
    long limit = 3L * 1024 * 1024 * 1024;
    byte[] beginning =
        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.75]},\"properties\":{\"id\":\"source\",\"object_type\":\"source\"}}]"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
      out.write(beginning);
      byte[] spaces = new byte[1024 * 1024];
      java.util.Arrays.fill(spaces, (byte) ' ');
      long left = limit - beginning.length - 1;
      while (left > 0) {
        int n = (int) Math.min(left, spaces.length);
        out.write(spaces, 0, n);
        left -= n;
      }
      out.write('}');
    }
    assertEquals(limit, Files.size(file));
    AtomicInteger count = new AtomicInteger();
    assertEquals(1, new GeoJsonInput(new ObjectMapper()).read(file, f -> count.incrementAndGet()));
    assertEquals(1, count.get());
  }
}
