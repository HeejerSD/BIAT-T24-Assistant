package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.UserLoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface UserLoginEventRepository extends JpaRepository<UserLoginEvent, Long> {
    List<UserLoginEvent> findByLoginAtBetweenOrderByLoginAtAsc(LocalDateTime from, LocalDateTime to);
}
