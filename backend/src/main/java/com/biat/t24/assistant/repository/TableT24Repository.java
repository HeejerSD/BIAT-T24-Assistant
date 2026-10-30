package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.ModuleT24;
import com.biat.t24.assistant.model.TableT24;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TableT24Repository extends JpaRepository<TableT24, Long> {
    Optional<TableT24> findByModuleAndNomTableIgnoreCase(ModuleT24 module, String nomTable);

    /** JOIN FETCH explicite (plutôt qu'une simple dérivation du nom de méthode) : le module doit rester utilisable
     *  (ex. getNomModule()) même après la fin de la transaction qui a exécuté cette requête — notamment dans
     *  {@code ChatController.handleStreamingAsk}, qui tourne dans un thread dédié ("chat-stream") sans session
     *  Hibernate active issue de la requête HTTP (pas d'Open Session In View là où c'est appelé) : un accès lazy
     *  à {@code t.getModule()} y lève sinon LazyInitializationException. */
    @Query("SELECT t FROM TableT24 t JOIN FETCH t.module m WHERE UPPER(m.nomModule) = UPPER(:nomModule)")
    List<TableT24> findByModule_NomModuleIgnoreCase(@Param("nomModule") String nomModule);

    Optional<TableT24> findByNomTableIgnoreCase(String nomTable);

    /** Nombre de tables par module (code, count), sans charger le contenu XML des tables en mémoire. */
    @Query("SELECT t.module.nomModule, COUNT(t) FROM TableT24 t GROUP BY t.module.nomModule")
    List<Object[]> countTablesByModule();

    /**
     * Tables (tous modules confondus) ayant une description mais pas encore de traduction française, avec les
     * modules de {@code priorityModules} en premier (le reste suit derrière, jamais exclu). Comparaison en IS NULL
     * uniquement (pas de {@code <> ''}/{@code = ''}) : Oracle ne gère pas bien l'égalité/inégalité directe sur des
     * colonnes CLOB, et {@code description}/{@code descriptionFr} ne sont de toute façon jamais mis à une chaîne
     * vide (null ou une vraie valeur).
     */
    @Query("SELECT t.id FROM TableT24 t WHERE t.description IS NOT NULL AND t.descriptionFr IS NULL "
            + "ORDER BY CASE WHEN t.module.nomModule IN :priorityModules THEN 0 ELSE 1 END, t.id")
    List<Long> findIdsNeedingFrenchTranslation(@Param("priorityModules") List<String> priorityModules);

    /**
     * Tables ayant une description mais pas encore de chunk "vue d'ensemble" indexé (voir
     * {@code Document.TABLE_OVERVIEW_COLUMN_MARKER}), avec les modules de {@code priorityModules} en premier.
     * Sert au rattrapage en tâche de fond des tables déjà importées avant l'introduction de ce chunk.
     */
    @Query("SELECT t.id FROM TableT24 t WHERE t.description IS NOT NULL AND NOT EXISTS "
            + "(SELECT 1 FROM Document d WHERE d.tableT24 = t AND d.columnName = :marker) "
            + "ORDER BY CASE WHEN t.module.nomModule IN :priorityModules THEN 0 ELSE 1 END, t.id")
    List<Long> findIdsMissingOverviewChunk(@Param("marker") String marker, @Param("priorityModules") List<String> priorityModules);
}
