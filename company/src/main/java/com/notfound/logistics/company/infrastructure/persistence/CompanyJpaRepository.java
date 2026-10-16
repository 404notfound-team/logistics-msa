package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface CompanyJpaRepository
    extends JpaRepository<Company, UUID>, JpaSpecificationExecutor<Company> {}
