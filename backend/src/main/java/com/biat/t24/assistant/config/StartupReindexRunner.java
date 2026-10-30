package com.biat.t24.assistant.config;

import com.biat.t24.assistant.controller.ChatController;
import com.biat.t24.assistant.model.Document;
import com.biat.t24.assistant.model.TableT24;
import com.biat.t24.assistant.repository.DocumentRepository;
import com.biat.t24.assistant.repository.TableT24Repository;
import com.biat.t24.assistant.service.RAG_service.AnswerService;
import com.biat.t24.assistant.service.RAG_service.DocumentService;
import com.biat.t24.assistant.service.T24ImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Relance automatiquement l'import/indexation à chaque démarrage du backend, en tâche de fond. {@code importAll()}
 * ne recalcule un embedding que si le contenu XML a changé ou si l'embedding est manquant en base : les tables
 * dont l'indexation avait échoué faute de FAISS/service d'embedding disponible (ex. module FX resté vide dans
 * FAISS) sont ainsi rattrapées tout seules, sans avoir à cliquer un bouton d'administration.
 *
 * Enchaîne ensuite la traduction française des descriptions de table, pour TOUS les modules (pas seulement ceux
 * visités) : une seule tâche de fond, qui reprend automatiquement là où elle s'était arrêtée si le backend
 * redémarre (les tables déjà traduites sont ignorées), plutôt que d'attendre que chaque module soit ouvert un
 * par un dans l'interface.
 */
@Component
public class StartupReindexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReindexRunner.class);

    /** Modules sur lesquels le chat est restreint (voir {@link ActiveModules}) : traduits en priorité, le reste
     *  des modules suit derrière sans jamais être exclu. */
    private static final List<String> PRIORITY_MODULES = ActiveModules.CODES;

    private final T24ImportService importService;
    private final TableT24Repository tableRepository;
    private final DocumentRepository documentRepository;
    private final AnswerService answerService;
    private final DocumentService documentService;

    public StartupReindexRunner(T24ImportService importService, TableT24Repository tableRepository,
                                 DocumentRepository documentRepository, AnswerService answerService,
                                 DocumentService documentService) {
        this.importService = importService;
        this.tableRepository = tableRepository;
        this.documentRepository = documentRepository;
        this.answerService = answerService;
        this.documentService = documentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(() -> {
            // Rattrapages sur les données déjà en base (rapides, ne dépendent pas d'un import frais) : passés
            // avant importAll() — qui parcourt le disque pour ~400 modules et peut prendre plusieurs minutes —
            // pour que le chat en bénéficie immédiatement plutôt que d'attendre la fin de ce balayage complet.
            backfillMissingTableOverviews();

            try {
                // Rattrape le périmètre restreint (ActiveModules) même si des modules hors périmètre avaient déjà
                // été poussés dans l'index global FAISS avant l'introduction de cette restriction.
                documentService.restrictGlobalIndexToActiveModules();
            } catch (Exception e) {
                log.error("Échec du nettoyage automatique de l'index global FAISS au démarrage", e);
            }

            try {
                log.info("Vérification/rattrapage automatique de l'indexation au démarrage...");
                int count = importService.importAll();
                log.info("Vérification d'indexation terminée ({} table(s) traitée(s)).", count);
            } catch (Exception e) {
                log.error("Échec de la vérification automatique d'indexation au démarrage", e);
            }
            // Désactivé temporairement : ces traductions appellent Ollama en lots répétés, monopolisant le seul
            // "slot" de génération disponible sur ce matériel (CPU, pas de traitement concurrent) et faisant
            // attendre plusieurs minutes toute question posée dans le chat juste après un redémarrage. Réactiver
            // une fois les traductions déjà bien avancées, ou si le chat n'est pas utilisé en parallèle.
            // translateAllPendingTableDescriptions();
            // translateAllPendingColumnDescriptions();
        }, "startup-reindex");
        thread.setDaemon(true);
        thread.start();
    }

    /** Nombre de tables traitées par lot pour le rattrapage du chunk "vue d'ensemble" (voir DocumentServiceImpl). */
    private static final int TABLE_OVERVIEW_BATCH_SIZE = 20;

    /**
     * Crée le chunk "vue d'ensemble" des tables déjà importées avant son introduction (voir
     * DocumentServiceImpl#backfillMissingTableOverviews) : sans lui, le chat ne peut jamais répondre à une
     * question conceptuelle ("quelle table gère X ?") pour ces tables. Boucle par petits lots jusqu'à épuisement,
     * comme les traductions ci-dessous : reprend automatiquement là où il s'était arrêté si le backend redémarre.
     */
    private void backfillMissingTableOverviews() {
        try {
            int total = 0;
            int done;
            do {
                done = documentService.backfillMissingTableOverviews(TABLE_OVERVIEW_BATCH_SIZE);
                total += done;
            } while (done > 0);
            if (total > 0) {
                log.info("Rattrapage des vues d'ensemble de table terminé ({} chunk(s) créé(s)).", total);
            }
        } catch (Exception e) {
            log.error("Échec du rattrapage automatique des vues d'ensemble de table", e);
        }
    }

    /** Nombre de descriptions traduites en une seule requête LLM : réduit d'autant le nombre d'aller-retours. */
    private static final int TRANSLATION_BATCH_SIZE = 15;

    private void translateAllPendingTableDescriptions() {
        try {
            List<Long> ids = tableRepository.findIdsNeedingFrenchTranslation(PRIORITY_MODULES);
            if (ids.isEmpty()) return;
            log.info("Traduction française de {} description(s) de table (tous modules), par lots de {}...",
                    ids.size(), TRANSLATION_BATCH_SIZE);
            int done = 0;
            for (int i = 0; i < ids.size(); i += TRANSLATION_BATCH_SIZE) {
                List<Long> batchIds = ids.subList(i, Math.min(i + TRANSLATION_BATCH_SIZE, ids.size()));
                List<TableT24> batch = batchIds.stream()
                        .map(id -> tableRepository.findById(id).orElse(null))
                        .filter(t -> t != null && t.getDescription() != null && !t.getDescription().isBlank()
                                && (t.getDescriptionFr() == null || t.getDescriptionFr().isBlank()))
                        .toList();
                if (batch.isEmpty()) continue;

                List<String> originals = batch.stream().map(TableT24::getDescription).toList();
                List<String> translated = answerService.translateBatchToFrench(originals);
                List<TableT24> actuallyTranslated = new java.util.ArrayList<>();
                for (int j = 0; j < batch.size(); j++) {
                    String original = originals.get(j);
                    String result = translated.get(j);
                    // Ne met en cache que si une vraie traduction a eu lieu : translateToFrench/translateBatchToFrench
                    // renvoient le texte original en repli si le LLM échoue, et le sauvegarder marquerait à tort
                    // cette table comme "traitée", sans jamais réessayer au prochain démarrage.
                    if (result != null && !result.equals(original)) {
                        batch.get(j).setDescriptionFr(result);
                        actuallyTranslated.add(batch.get(j));
                    }
                }
                tableRepository.saveAll(actuallyTranslated);

                done += actuallyTranslated.size();
                log.info("Traduction des descriptions de table : {}/{}", done, ids.size());
            }
            log.info("Traduction française des descriptions de table terminée ({} table(s)).", done);
        } catch (Exception e) {
            log.error("Échec de la traduction automatique des descriptions de table", e);
        }
    }

    /** Même mécanisme que {@link #translateAllPendingTableDescriptions()} mais pour les descriptions de colonne
     *  (page de détail d'une table) : rempli le cache {@code Document.descriptionFr} en tâche de fond, pour que
     *  l'ouverture d'une table dans l'interface n'attende jamais un appel LLM en direct. */
    private void translateAllPendingColumnDescriptions() {
        try {
            List<Long> ids = documentRepository.findIdsNeedingFrenchTranslation(PRIORITY_MODULES);
            if (ids.isEmpty()) return;
            log.info("Traduction française de {} description(s) de colonne (tous modules), par lots de {}...",
                    ids.size(), TRANSLATION_BATCH_SIZE);
            int done = 0;
            for (int i = 0; i < ids.size(); i += TRANSLATION_BATCH_SIZE) {
                List<Long> batchIds = ids.subList(i, Math.min(i + TRANSLATION_BATCH_SIZE, ids.size()));
                List<Document> batch = new java.util.ArrayList<>();
                List<String> originals = new java.util.ArrayList<>();
                for (Long id : batchIds) {
                    Document doc = documentRepository.findById(id).orElse(null);
                    if (doc == null || (doc.getDescriptionFr() != null && !doc.getDescriptionFr().isBlank())) continue;
                    String description = ChatController.extractDescription(doc.getContent());
                    if (description.isBlank()) continue;
                    batch.add(doc);
                    originals.add(description);
                }
                if (batch.isEmpty()) continue;

                List<String> translated = answerService.translateBatchToFrench(originals);
                List<Document> actuallyTranslated = new java.util.ArrayList<>();
                for (int j = 0; j < batch.size(); j++) {
                    String original = originals.get(j);
                    String result = translated.get(j);
                    if (result != null && !result.equals(original)) {
                        batch.get(j).setDescriptionFr(result);
                        actuallyTranslated.add(batch.get(j));
                    }
                }
                documentRepository.saveAll(actuallyTranslated);

                done += actuallyTranslated.size();
                log.info("Traduction des descriptions de colonne : {}/{}", done, ids.size());
            }
            log.info("Traduction française des descriptions de colonne terminée ({} colonne(s)).", done);
        } catch (Exception e) {
            log.error("Échec de la traduction automatique des descriptions de colonne", e);
        }
    }
}
