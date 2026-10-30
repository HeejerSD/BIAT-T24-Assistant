package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.ModuleT24;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModuleT24Repository extends JpaRepository<ModuleT24, Long> {
    Optional<ModuleT24> findByNomModuleIgnoreCase(String nomModule);
}
