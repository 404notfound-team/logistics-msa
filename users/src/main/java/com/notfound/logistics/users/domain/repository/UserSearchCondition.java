package com.notfound.logistics.users.domain.repository;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import java.util.UUID;

/** 사용자 목록 검색 조건입니다. null인 조건은 적용하지 않습니다. username·name은 부분 일치입니다. */
public record UserSearchCondition(
    String username, String name, UserRoleEnum role, UUID companyId, UUID hubId) {}
