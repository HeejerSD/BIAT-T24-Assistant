package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.PasswordResetRequest;
import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD standard seulement : conservé comme journal d'audit des réinitialisations (voir PasswordResetController#resetSelf),
 *  plus aucune page d'administration ne le consulte. */
public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, Long> {
}
