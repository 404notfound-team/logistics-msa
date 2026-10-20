package com.notfound.logistics.users.application.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;

/** 회원가입 입력값. presentation 요청 DTO에 application이 의존하지 않도록 분리한다. */
public record RegisterUserCommand(
    String username,
    String name,
    String password,
    UserRoleEnum role,
    String slackId,
    String companyName,
    String hubName) {}
