package com.biat.t24.assistant.service.RAG_service;

import com.biat.t24.assistant.model.Document;

import java.util.List;
import java.util.Set;

public interface RetrieverService {

    /** Un chunk retrouvé et son score de similarité cosinus avec la question (entre -1 et 1, plus haut = plus pertinent). */
    record ScoredDocument(Document document, double score) {}

    /**
     * Retrouve les {@code topK} chunks les plus pertinents pour {@code question}.
     * Si {@code tableName} est fourni, la recherche est restreinte à cette table ;
     * sinon si {@code module} est fourni, restreinte à ce module ; sinon elle porte sur tout l'index.
     */
    List<ScoredDocument> search(String question, String module, String tableName, int topK);

    /**
     * Contexte d'une question GÉNÉRALE sur un module : d'abord un document de synthèse (libellé officiel, nombre et
     * liste des tables documentées), puis les {@code topK} vues d'ensemble de tables les plus proches de
     * {@code question} — jamais des champs isolés, qui feraient dériver la réponse vers un détail arbitraire.
     * {@code excludeTables} écarte les tables déjà présentées aux tours précédents (approfondissement).
     */
    List<ScoredDocument> searchModuleOverview(String question, String module, int topK, Set<String> excludeTables);

    /** Vue d'ensemble puis jusqu'à {@code perTable} champs documentés de chacune des tables (comparaison, suivi). */
    List<ScoredDocument> documentsOfTables(List<String> tableNames, int perTable);

    /**
     * Champs (parmi les tables données, ou, à défaut, aucun) dont la Description contient des règles de
     * validation explicites ("Validation Rules"), au plus 2 par table, pour répondre sur le contrôle de saisie à
     * partir de ce qui est réellement documenté.
     */
    List<ScoredDocument> validationDocuments(List<String> tableNames, int limit);

    /**
     * Champs (parmi les tables données) dont la Description contient un exemple documenté ("Example 1: ...",
     * "for example"), les plus concis d'abord : permet de répondre à "donne un exemple" avec un exemple réellement
     * présent dans la documentation plutôt qu'un exemple inventé par le modèle.
     */
    List<ScoredDocument> exampleDocuments(List<String> tableNames, int limit);
}
