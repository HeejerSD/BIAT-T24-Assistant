package com.biat.t24.assistant.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Un chunk indexable : une ligne = une seule colonne d'une table T24 (utilisé par la recherche RAG). */
@Entity
@Table(name = "DOCUMENT")
public class Document {

    /**
     * Valeur de {@link #columnName} pour un chunk qui décrit la table dans son ensemble (vue d'ensemble extraite
     * de {@code <ovdesc>} dans le HelpText T24), pas un champ précis : permet à la recherche sémantique de
     * répondre à des questions conceptuelles ("quelle table gère X ?", "à quoi sert ce module ?") à partir de la
     * vraie documentation, sans jamais être confondu avec un vrai nom de colonne (ne matche aucune recherche par
     * nom de colonne) ni affiché comme une carte de résultat "champ".
     */
    public static final String TABLE_OVERVIEW_COLUMN_MARKER = "__TABLE_OVERVIEW__";

    /**
     * Valeur de {@link #columnName} d'un document de SYNTHÈSE d'un module (libellé, liste des tables), construit à la
     * volée pour répondre aux questions générales — jamais persisté en base, jamais affiché comme source.
     */
    public static final String MODULE_SUMMARY_COLUMN_MARKER = "__MODULE_SUMMARY__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module", nullable = false, length = 50)
    private String module;

    @Column(name = "table_name", nullable = false, length = 150)
    private String tableName;

    @Column(name = "column_name", nullable = false, length = 150)
    private String columnName;

    /** Texte lisible envoyé à l'embedding / au LLM : module, table, colonne, type, obligatoire, description. */
    @Lob
    @Column(name = "content", columnDefinition = "CLOB", nullable = false)
    private String content;

    /** Vecteur d'embedding sérialisé en JSON (ex: "[0.012,-0.045,...]"). Null si l'appel d'embedding a échoué. */
    @Lob
    @Column(name = "embedding", columnDefinition = "CLOB")
    private String embedding;

    /** Description de la colonne traduite en français, mise en cache par la tâche de fond de traduction (voir
     *  StartupReindexRunner) : null tant que non traduite, jamais recalculée à la volée dans une requête HTTP. */
    @Lob
    @Column(name = "description_fr", columnDefinition = "CLOB")
    private String descriptionFr;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_t24_id", nullable = false)
    private TableT24 tableT24;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public String getDescriptionFr() { return descriptionFr; }
    public void setDescriptionFr(String descriptionFr) { this.descriptionFr = descriptionFr; }

    public TableT24 getTableT24() { return tableT24; }
    public void setTableT24(TableT24 tableT24) { this.tableT24 = tableT24; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
