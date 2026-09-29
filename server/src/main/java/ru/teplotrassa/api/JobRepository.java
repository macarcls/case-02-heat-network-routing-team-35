package ru.teplotrassa.api;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface JobRepository extends CrudRepository<JobRecord, UUID> {}
