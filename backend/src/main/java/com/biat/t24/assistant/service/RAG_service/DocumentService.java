package com.biat.t24.assistant.service.RAG_service;

import com.biat.t24.assistant.model.TableT24;

public interface DocumentService {

    /**
     * Reconstruit l'indexation d'une table : supprime uniquement les chunks déjà liés
     * à cette table (table_t24_id), puis les recrée à partir du XML brut de la table.
     * Les chunks des autres tables ne sont jamais touchés.
     */
    void reindexTable(TableT24 table);

    /** Supprime les chunks liés à une table, sans les recréer. */
    void deleteByTable(Long tableT24Id);

    /** Vrai si au moins un chunk de cette table n'a pas encore de vecteur d'embedding (ex: calculé alors que le service d'embedding était indisponible). */
    boolean hasMissingEmbeddings(Long tableT24Id);

    /**
     * Pousse dans FAISS les vecteurs déjà présents en base (colonne EMBEDDING), sans rappeler l'API d'embedding.
     * Utile pour peupler un microservice FAISS neuf/redémarré à partir de données déjà indexées.
     * Retourne le nombre de chunks poussés.
     */
    int backfillFaiss();

    /**
     * Retire de l'index vectoriel global (scope {@code __global__}) les chunks des modules hors du périmètre
     * restreint ({@link com.biat.t24.assistant.config.ActiveModules}) : ces modules restent en base et dans leur
     * propre scope FAISS, seule la recherche "tous modules" (aucun module sélectionné) ne les considère plus,
     * pour rester rapide. Retourne le nombre de vecteurs retirés.
     */
    int restrictGlobalIndexToActiveModules();

    /**
     * Crée, embed et indexe le chunk "vue d'ensemble" (voir {@code Document.TABLE_OVERVIEW_COLUMN_MARKER}) des
     * tables déjà importées qui n'en ont pas encore (ex: importées avant l'introduction de ce chunk) : sans lui,
     * la recherche sémantique ne peut jamais répondre à une question conceptuelle sur le rôle d'une table ("quelle
     * table gère X ?"). Traite un lot à la fois ({@code batchSize} tables), pour être appelé en boucle depuis une
     * tâche de fond sans bloquer. Retourne le nombre de chunks créés dans ce lot (0 s'il n'y en a plus).
     */
    int backfillMissingTableOverviews(int batchSize);
}
