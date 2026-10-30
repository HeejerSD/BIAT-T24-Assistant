package com.biat.t24.assistant.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "TABLE_T24")
public class TableT24 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private ModuleT24 module;

    /** Nom du fichier XML du module, ex: AC.ACCOUNT.OPENING (issu de AC.ACCOUNT.OPENING.xml) */
    @Column(name = "nom_table", nullable = false, length = 150)
    private String nomTable;

    /** Contenu brut du fichier XML T24 */
    @Lob
    @Column(name = "data", columnDefinition = "CLOB")
    private String data;

    /** Description de la table elle-même, extraite de la section &lt;overview&gt;&lt;ovdesc&gt; du XML T24 (en anglais, langue source). Null si absente. */
    @Lob
    @Column(name = "description", columnDefinition = "CLOB")
    private String description;

    /** Traduction française de {@link #description}, calculée une fois à l'import (évite de retraduire à chaque affichage). */
    @Lob
    @Column(name = "description_fr", columnDefinition = "CLOB")
    private String descriptionFr;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ModuleT24 getModule() { return module; }
    public void setModule(ModuleT24 module) { this.module = module; }

    public String getNomTable() { return nomTable; }
    public void setNomTable(String nomTable) { this.nomTable = nomTable; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getData() {
        return data;
    }

    public void updateData(String data) {
        this.data = data;
        this.updatedAt = LocalDateTime.now();
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDescriptionFr() { return descriptionFr; }
    public void setDescriptionFr(String descriptionFr) { this.descriptionFr = descriptionFr; }
}
