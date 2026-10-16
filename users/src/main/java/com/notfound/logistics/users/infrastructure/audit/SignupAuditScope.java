package com.notfound.logistics.users.infrastructure.audit;

import java.util.function.Supplier;

/**
 * 로그인 전이라 인증 주체가 없는 회원가입 저장을 지정된 시스템 작업자로 기록하도록 표시합니다. 이 범위 밖에서 인증 주체 없이 저장하면 감사 작업자를 정할 수 없어
 * 실패합니다.
 */
public final class SignupAuditScope {
  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private SignupAuditScope() {}

  public static <T> T run(Supplier<T> action) {
    Boolean previous = ACTIVE.get();
    ACTIVE.set(Boolean.TRUE);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        ACTIVE.remove();
      } else {
        ACTIVE.set(previous);
      }
    }
  }

  static boolean isActive() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }
}
