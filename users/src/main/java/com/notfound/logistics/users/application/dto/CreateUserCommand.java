package com.notfound.logistics.users.application.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import java.util.UUID;

/** MASTER의 사용자 생성 입력값. presentation 요청 DTO에 application이 의존하지 않도록 분리한다. */
public record CreateUserCommand(
    String username,
    String name,
    String password,
    UserRoleEnum role,
    String slackId,
    UUID companyId,
    UUID hubId,
    Boolean isDeliveryStaff) {}
