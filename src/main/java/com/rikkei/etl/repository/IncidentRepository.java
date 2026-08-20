package com.rikkei.etl.repository;

import com.rikkei.etl.entity.IncidentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentRepository extends JpaRepository<IncidentReport, Long> {
}
