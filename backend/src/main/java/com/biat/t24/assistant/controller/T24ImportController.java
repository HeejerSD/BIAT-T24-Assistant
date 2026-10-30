package com.biat.t24.assistant.controller;

import com.biat.t24.assistant.model.ModuleT24;
import com.biat.t24.assistant.model.TableT24;
import com.biat.t24.assistant.repository.ModuleT24Repository;
import com.biat.t24.assistant.repository.TableT24Repository;
import com.biat.t24.assistant.service.T24ImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/t24")
@CrossOrigin(origins = "http://localhost:4200")
public class T24ImportController {

    private final T24ImportService importService;
    private final ModuleT24Repository moduleRepository;
    private final TableT24Repository tableRepository;

    public T24ImportController(T24ImportService importService,
                                ModuleT24Repository moduleRepository,
                                TableT24Repository tableRepository) {
        this.importService = importService;
        this.moduleRepository = moduleRepository;
        this.tableRepository = tableRepository;
    }

    /** Scanne le répertoire HelpText et (re)charge MODULE_T24 / TABLE_T24 en base. */
    @PostMapping("/import")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<?> importAll() {
        int count = importService.importAll();
        return ResponseEntity.ok(Map.of("tablesImportees", count));
    }

    @GetMapping("/modules")
    public ResponseEntity<List<String>> listModules() {
        return ResponseEntity.ok(moduleRepository.findAll().stream()
                .map(ModuleT24::getNomModule)
                .sorted()
                .toList());
    }

    @GetMapping("/modules/{nomModule}/tables")
    public ResponseEntity<List<String>> listTables(@PathVariable String nomModule) {
        return ResponseEntity.ok(tableRepository.findByModule_NomModuleIgnoreCase(nomModule).stream()
                .map(TableT24::getNomTable)
                .sorted()
                .toList());
    }

    @GetMapping("/tables/{nomTable}")
    public ResponseEntity<?> getTableData(@PathVariable String nomTable) {
        return tableRepository.findByNomTableIgnoreCase(nomTable)
                .map(t -> ResponseEntity.ok(Map.of(
                        "module", t.getModule().getNomModule(),
                        "nomTable", t.getNomTable(),
                        "data", t.getData())))
                .orElse(ResponseEntity.notFound().build());
    }
}
