package ru.teplotrassa.api;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Spring Data projection; feature batches use JdbcTemplate for bounded memory. */
@Table("jobs")
public class JobRecord {
  @Id @Column("id") public UUID id;
  @Column("status") public String status;
  @Column("cancelled") public boolean cancelled;
}
