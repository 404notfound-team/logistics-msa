package com.notfound.logistics.hub.infrastructure.persistence.stock;

import com.notfound.logistics.hub.domain.model.stock.StockDeduction;
import com.notfound.logistics.hub.domain.repository.stock.StockDeductionRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockDeductionJpaRepository
    extends JpaRepository<StockDeduction, UUID>, StockDeductionRepository {}
