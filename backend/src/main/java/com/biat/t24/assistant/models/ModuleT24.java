package com.biat.t24.assistant.model;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "MODULE_T24")
public class ModuleT24 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom_module", nullable = false, unique = true, length = 50)
    private String nomModule;

    /** Libellé humain du module (ex. "Accounts" pour AC), extrait de portal-help-menu.xml. Null si non trouvé. */
    @Column(name = "label", length = 200)
    private String label;

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TableT24> listTable = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNomModule() { return nomModule; }
    public void setNomModule(String nomModule) { this.nomModule = nomModule; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public void addTable(TableT24 table) {
        table.setModule(this);
        listTable.add(table);
    }

    public void removeTable(Long tableId) {
        listTable.removeIf(t -> t.getId() != null && t.getId().equals(tableId));
    }

    public List<TableT24> getTables() {
        return listTable;
    }
}
