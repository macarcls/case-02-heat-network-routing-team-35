package ru.teplotrassa.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.*;
import org.springframework.jdbc.core.*;

public final class JdbcFeatureStore implements FeatureStore {
  private List<Map<String, Object>> knownDiagnostics;

  public void setDiagnostics(List<Map<String, Object>> value) {
    knownDiagnostics = value;
  }

  @Override
  public List<Map<String, Object>> diagnostics() {
    return knownDiagnostics == null ? InputDiagnostics.inspect(this) : knownDiagnostics;
  }

  private final JdbcTemplate db;
  private final UUID dataset;
  private final ObjectMapper json;
  private final boolean postgres;
  private final Map<String, Feature> cache =
      new LinkedHashMap<String, Feature>(512, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Feature> e) {
          return size() > 512;
        }
      };

  public JdbcFeatureStore(JdbcTemplate db, UUID dataset, ObjectMapper json) {
    this.db = db;
    this.dataset = dataset;
    this.json = json;
    try (Connection c = db.getDataSource().getConnection()) {
      postgres = c.getMetaData().getDatabaseProductName().equals("PostgreSQL");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private Feature row(ResultSet rs, int n) throws SQLException {
    try {
      return new Feature(
          rs.getString("id"),
          rs.getString("object_type"),
          json.readTree(rs.getString("properties")),
          new WKBReader().read(rs.getBytes("geometry")));
    } catch (Exception e) {
      throw new SQLException("Ошибка чтения геометрии", e);
    }
  }

  public Feature get(String id) {
    if (cache.containsKey(id)) return cache.get(id);
    List<Feature> r =
        db.query("SELECT * FROM features WHERE dataset_id=? AND id=?", this::row, dataset, id);
    Feature f = r.isEmpty() ? null : r.get(0);
    if (f != null && f.geometry.getNumPoints() <= 500 && f.properties.toString().length() <= 4096)
      cache.put(id, f);
    return f;
  }

  public Iterable<String> ids(String type) {
    return () ->
        new Iterator<String>() {
          String after = "";
          Iterator<String> batch = Collections.emptyIterator();
          boolean end = false;

          public boolean hasNext() {
            if (!batch.hasNext() && !end) {
              List<String> items =
                  db.queryForList(
                      "SELECT id FROM features WHERE dataset_id=? AND object_type=? AND id>? ORDER"
                          + " BY id LIMIT 1000",
                      String.class,
                      dataset,
                      type,
                      after);
              end = items.size() < 1000;
              batch = items.iterator();
            }
            return batch.hasNext();
          }

          public String next() {
            if (!hasNext()) throw new NoSuchElementException();
            return after = batch.next();
          }
        };
  }

  private String bounds() {
    return postgres
        ? "box(point(minx,miny),point(maxx,maxy)) && box(point(?,?),point(?,?))"
        : "maxx>=? AND maxy>=? AND minx<=? AND miny<=?";
  }

  public List<Feature> near(Envelope e) {
    return db.execute(
        (ConnectionCallback<List<Feature>>)
            connection -> {
              boolean auto = connection.getAutoCommit();
              if (auto) connection.setAutoCommit(false);
              try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT * FROM features WHERE dataset_id=? AND "
                          + bounds()
                          + " ORDER BY id LIMIT 20001")) {
                statement.setObject(1, dataset);
                statement.setDouble(2, e.getMinX());
                statement.setDouble(3, e.getMinY());
                statement.setDouble(4, e.getMaxX());
                statement.setDouble(5, e.getMaxY());
                statement.setFetchSize(64);
                List<Feature> result = new ArrayList<>();
                long bytes = 0;
                try (ResultSet rs = statement.executeQuery()) {
                  while (rs.next()) {
                    byte[] wkb = rs.getBytes("geometry");
                    String props = rs.getString("properties");
                    bytes += wkb.length * 5L + props.length() * 4L;
                    if (result.size() >= 20000 || bytes > 128L * 1024 * 1024)
                      throw new IllegalArgumentException(
                          "SEARCH_WINDOW_DENSITY_LIMIT: окно поиска превышает 20000 объектов или"
                              + " 128 МиБ. Уменьшите область исходного набора.");
                    try {
                      result.add(
                          new Feature(
                              rs.getString("id"),
                              rs.getString("object_type"),
                              json.readTree(props),
                              new WKBReader().read(wkb)));
                    } catch (Exception error) {
                      throw new SQLException("Ошибка геометрии", error);
                    }
                  }
                }
                if (auto) connection.commit();
                return result;
              } catch (Exception error) {
                if (auto) connection.rollback();
                throw error;
              } finally {
                if (auto) connection.setAutoCommit(true);
              }
            });
  }

  public List<String> viewIds(Envelope e) {
    return db.queryForList(
        "SELECT id FROM features WHERE dataset_id=? AND "
            + bounds()
            + " ORDER BY CASE object_type WHEN 'oks_future' THEN 0 WHEN 'oks_connection_point' THEN"
            + " 1 WHEN 'source' THEN 2 WHEN 'heat_network' THEN 3 ELSE 4 END,id LIMIT 5001",
        String.class,
        dataset,
        e.getMinX(),
        e.getMinY(),
        e.getMaxX(),
        e.getMaxY());
  }

  public void replace(Feature f) {
    db.update(
        "UPDATE features SET object_type=?,properties=? WHERE dataset_id=? AND id=?",
        f.type,
        f.properties.toString(),
        dataset,
        f.id);
    cache.put(f.id, f);
  }

  public static void insert(JdbcTemplate db, UUID dataset, List<Feature> batch) {
    db.batchUpdate(
        "INSERT INTO features(dataset_id,id,object_type,properties,geometry,minx,maxx,miny,maxy)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        batch,
        100,
        (ps, f) -> {
          Envelope e = f.geometry.getEnvelopeInternal();
          ps.setObject(1, dataset);
          ps.setString(2, f.id);
          ps.setString(3, f.type);
          ps.setString(4, f.properties.toString());
          ps.setBytes(5, new WKBWriter().write(f.geometry));
          ps.setDouble(6, e.getMinX());
          ps.setDouble(7, e.getMaxX());
          ps.setDouble(8, e.getMinY());
          ps.setDouble(9, e.getMaxY());
        });
  }
}
