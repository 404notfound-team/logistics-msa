package com.notfound.logistics.hub.application.service.hub;

import com.notfound.logistics.hub.application.dto.hub.HubResult;
import com.notfound.logistics.hub.application.service.QuerySort;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import com.notfound.logistics.hub.domain.model.hub.Hub;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubQueryService {

  private final HubRepository hubRepository;

  public Page<HubResult> getHubs(int page, int size, String direction) {
    return getHubs(page, size, direction, null, null);
  }

  public Page<HubResult> getHubs(int page, int size, String direction, String name, String sort) {

    Pageable pageable = PageRequest.of(page, size, QuerySort.resolve(sort, direction));
    String pattern = name == null || name.isBlank() ? null : containsPattern(name.strip());

    if (pageable.getOffset() >= Integer.MAX_VALUE) {
      long totalElements =
          pattern == null ? hubRepository.count() : hubRepository.countByNamePattern(pattern);

      return new PageImpl<>(List.of(), pageable, totalElements);
    }

    return (pattern == null
            ? hubRepository.findAll(pageable)
            : hubRepository.searchByName(pattern, pageable))
        .map(HubResult::from);
  }

  private static String containsPattern(String name) {
    return "%"
        + name.toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        + "%";
  }

  public HubResult getHub(UUID id) {
    Hub hub =
        hubRepository
            .findById(id)
            .orElseThrow(() -> new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    return HubResult.from(hub);
  }

  public List<HubResult> getHubsByName(String name) {
    return hubRepository.findByName(name).stream().map(HubResult::from).toList();
  }
}
