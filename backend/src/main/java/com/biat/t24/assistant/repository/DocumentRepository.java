package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByTableT24_Id(Long tableT24Id);
    void deleteByTableT24_Id(Long tableT24Id);
    long countByTableT24_Id(Long tableT24Id);
    boolean existsByTableT24_IdAndEmbeddingIsNull(Long tableT24Id);

    List<Document> findByTableNameIgnoreCase(String tableName);
    List<Document> findByModuleIgnoreCase(String module);

    /** Documents d'un module ayant une colonne donnée — ex. les seules vues d'ensemble de tables (marqueur). */
    List<Document> findByModuleIgnoreCaseAndColumnName(String module, String columnName);

    List<Document> findByColumnNameIgnoreCaseAndTableNameIgnoreCase(String columnName, String tableName);
    List<Document> findByColumnNameIgnoreCaseAndModuleIgnoreCase(String columnName, String module);
    List<Document> findByColumnNameIgnoreCase(String columnName);

    @Query("SELECT d.id FROM Document d WHERE d.descriptionFr IS NULL "
            + "ORDER BY CASE WHEN d.module IN :priorityModules THEN 0 ELSE 1 END, d.id")
    List<Long> findIdsNeedingFrenchTranslation(@Param("priorityModules") List<String> priorityModules);

    @Query("SELECT DISTINCT d.module FROM Document d")
    List<String> findDistinctModules();

    /** Noms de colonnes distincts d'une table, pour la reconnaissance floue (tolérance aux fautes de frappe). */
    @Query("SELECT DISTINCT d.columnName FROM Document d WHERE UPPER(d.tableName) = UPPER(:tableName)")
    List<String> findDistinctColumnNamesByTableNameIgnoreCase(@Param("tableName") String tableName);

    /** Idem à l'échelle du module (utilisé seulement en dernier recours, aucune table précise sélectionnée). */
    @Query("SELECT DISTINCT d.columnName FROM Document d WHERE UPPER(d.module) = UPPER(:module)")
    List<String> findDistinctColumnNamesByModuleIgnoreCase(@Param("module") String module);

    /** Noms de tables distincts d'un module, pour la reconnaissance floue d'un nom de TABLE mal orthographié
     *  (ex: "acount" -> "ACCOUNT") — voir findDistinctColumnNamesByModuleIgnoreCase pour l'équivalent colonne. */
    @Query("SELECT DISTINCT d.tableName FROM Document d WHERE UPPER(d.module) = UPPER(:module)")
    List<String> findDistinctTableNamesByModuleIgnoreCase(@Param("module") String module);
}
