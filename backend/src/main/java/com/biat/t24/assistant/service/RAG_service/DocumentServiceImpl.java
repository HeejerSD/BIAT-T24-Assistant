package com.biat.t24.assistant.service.RAG_service;

import com.biat.t24.assistant.config.ActiveModules;
import com.biat.t24.assistant.model.Document;
import com.biat.t24.assistant.model.TableT24;
import com.biat.t24.assistant.repository.DocumentRepository;
import com.biat.t24.assistant.repository.TableT24Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GLOBAL_SCOPE = "__global__";

    private final DocumentRepository documentRepository;
    private final TableT24Repository tableT24Repository;
    private final EmbeddingService embeddingService;
    private final FaissClient faissClient;

    public DocumentServiceImpl(DocumentRepository documentRepository, TableT24Repository tableT24Repository,
                                EmbeddingService embeddingService, FaissClient faissClient) {
        this.documentRepository = documentRepository;
        this.tableT24Repository = tableT24Repository;
        this.embeddingService = embeddingService;
        this.faissClient = faissClient;
    }

    @Override
    @Transactional
    public void reindexTable(TableT24 table) {
        String module = table.getModule().getNomModule();
        String tableName = table.getNomTable();

        List<Long> oldIds = documentRepository.findByTableT24_Id(table.getId()).stream().map(Document::getId).toList();
        documentRepository.deleteByTableT24_Id(table.getId());
        removeFromFaiss(module, tableName, oldIds);

        List<FieldInfo> fields = parseFields(table.getData());
        List<Document> chunks = new ArrayList<>(fields.size() + 1);
        for (FieldInfo field : fields) {
            Document doc = new Document();
            doc.setModule(module);
            doc.setTableName(tableName);
            doc.setColumnName(field.name());
            doc.setTableT24(table);
            doc.setContent(buildChunkText(table, field));
            chunks.add(doc);
        }

        // Chunk supplémentaire pour la table dans son ensemble (pas un champ précis) : sans lui, la recherche
        // sémantique ne peut jamais répondre à une question conceptuelle ("quelle table gère la fermeture de
        // compte ?"), puisque seuls des champs individuels sont indexés sinon.
        if (table.getDescription() != null && !table.getDescription().isBlank()) {
            Document overview = new Document();
            overview.setModule(module);
            overview.setTableName(tableName);
            overview.setColumnName(Document.TABLE_OVERVIEW_COLUMN_MARKER);
            overview.setTableT24(table);
            overview.setContent(buildTableOverviewText(table));
            chunks.add(overview);
        }

        List<float[]> vectors = embeddingService.embedBatch(chunks.stream().map(Document::getContent).toList());
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(serializeVector(vectors.get(i)));
        }

        chunks = documentRepository.saveAll(chunks);
        upsertToFaiss(module, tableName, chunks, vectors);
        log.info("{} chunk(s) indexé(s) pour la table {}", chunks.size(), tableName);
    }

    /** Retire les anciens chunks de cette table des index FAISS (table + module, + index global si module actif). Best-effort : ne bloque pas le reindex. */
    private void removeFromFaiss(String module, String tableName, List<Long> ids) {
        if (ids.isEmpty()) return;
        try {
            faissClient.delete(tableName, ids);
            faissClient.delete(module, ids);
            if (ActiveModules.CODE_SET.contains(module.toUpperCase())) faissClient.delete(GLOBAL_SCOPE, ids);
        } catch (Exception e) {
            log.warn("Impossible de supprimer les anciens vecteurs FAISS (service indisponible ?) : {}", e.getMessage());
        }
    }

    /**
     * Indexe les nouveaux chunks dans FAISS (table + module, toujours ; index global uniquement si le module fait
     * partie du périmètre restreint {@link ActiveModules}) : la recherche "tous modules" (aucun module sélectionné)
     * reste ainsi rapide, sans bruit venant de modules hors périmètre. Best-effort : ne bloque pas le reindex.
     */
    private void upsertToFaiss(String module, String tableName, List<Document> chunks, List<float[]> vectors) {
        List<FaissClient.IdVector> items = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Long id = chunks.get(i).getId();
            float[] vector = vectors.get(i);
            if (id != null && vector != null) items.add(new FaissClient.IdVector(id, vector));
        }
        if (items.isEmpty()) return;
        try {
            faissClient.upsert(tableName, items);
            faissClient.upsert(module, items);
            if (ActiveModules.CODE_SET.contains(module.toUpperCase())) faissClient.upsert(GLOBAL_SCOPE, items);
        } catch (Exception e) {
            log.warn("Impossible d'indexer les nouveaux vecteurs dans FAISS (service indisponible ?) : {}", e.getMessage());
        }
    }

    @Override
    public int backfillFaiss() {
        int pushed = 0;
        int page = 0;
        Pageable pageable = PageRequest.of(page, 500);
        while (true) {
            List<Document> batch = documentRepository.findAll(pageable).getContent();
            if (batch.isEmpty()) break;

            Map<String, List<FaissClient.IdVector>> byTable = new HashMap<>();
            Map<String, List<FaissClient.IdVector>> byModule = new HashMap<>();
            List<FaissClient.IdVector> global = new ArrayList<>();

            for (Document doc : batch) {
                float[] vector = deserializeVector(doc.getEmbedding());
                if (vector == null) continue;
                FaissClient.IdVector item = new FaissClient.IdVector(doc.getId(), vector);
                byTable.computeIfAbsent(doc.getTableName(), k -> new ArrayList<>()).add(item);
                byModule.computeIfAbsent(doc.getModule(), k -> new ArrayList<>()).add(item);
                if (ActiveModules.CODE_SET.contains(doc.getModule().toUpperCase())) global.add(item);
                pushed++;
            }

            try {
                byTable.forEach(faissClient::upsert);
                byModule.forEach(faissClient::upsert);
                faissClient.upsert(GLOBAL_SCOPE, global);
            } catch (Exception e) {
                log.error("Échec du backfill FAISS sur la page {} (service indisponible ?) : {}", page, e.getMessage());
                break;
            }

            page++;
            pageable = PageRequest.of(page, 500);
        }
        log.info("{} chunk(s) repoussé(s) dans FAISS depuis les embeddings déjà stockés en base", pushed);
        return pushed;
    }

    @Override
    public int restrictGlobalIndexToActiveModules() {
        List<String> inactiveModules = documentRepository.findDistinctModules().stream()
                .filter(m -> m != null && !ActiveModules.CODE_SET.contains(m.toUpperCase()))
                .toList();

        int removed = 0;
        for (String module : inactiveModules) {
            List<Long> ids = documentRepository.findByModuleIgnoreCase(module).stream().map(Document::getId).toList();
            if (ids.isEmpty()) continue;
            try {
                faissClient.delete(GLOBAL_SCOPE, ids);
                removed += ids.size();
            } catch (Exception e) {
                log.warn("Impossible de retirer les vecteurs du module {} de l'index global FAISS : {}", module, e.getMessage());
            }
        }
        log.info("{} vecteur(s) retiré(s) de l'index global FAISS pour {} module(s) hors périmètre.", removed, inactiveModules.size());
        return removed;
    }

    @Override
    @Transactional
    public int backfillMissingTableOverviews(int batchSize) {
        List<Long> ids = tableT24Repository.findIdsMissingOverviewChunk(Document.TABLE_OVERVIEW_COLUMN_MARKER, ActiveModules.CODES);
        if (ids.isEmpty()) return 0;
        List<Long> batchIds = ids.size() > batchSize ? ids.subList(0, batchSize) : ids;

        List<TableT24> tables = batchIds.stream()
                .map(id -> tableT24Repository.findById(id).orElse(null))
                .filter(t -> t != null && t.getDescription() != null && !t.getDescription().isBlank())
                .toList();
        if (tables.isEmpty()) return 0;

        List<Document> overviews = new ArrayList<>(tables.size());
        for (TableT24 table : tables) {
            Document overview = new Document();
            overview.setModule(table.getModule().getNomModule());
            overview.setTableName(table.getNomTable());
            overview.setColumnName(Document.TABLE_OVERVIEW_COLUMN_MARKER);
            overview.setTableT24(table);
            overview.setContent(buildTableOverviewText(table));
            overviews.add(overview);
        }

        List<float[]> vectors = embeddingService.embedBatch(overviews.stream().map(Document::getContent).toList());
        for (int i = 0; i < overviews.size(); i++) {
            overviews.get(i).setEmbedding(serializeVector(vectors.get(i)));
        }
        overviews = documentRepository.saveAll(overviews);

        for (int i = 0; i < overviews.size(); i++) {
            Document overview = overviews.get(i);
            float[] vector = vectors.get(i);
            if (vector == null || overview.getId() == null) continue;
            try {
                upsertToFaiss(overview.getModule(), overview.getTableName(), List.of(overview), List.of(vector));
            } catch (Exception e) {
                log.warn("Impossible d'indexer le chunk de vue d'ensemble de {} dans FAISS : {}", overview.getTableName(), e.getMessage());
            }
        }
        log.info("{} chunk(s) de vue d'ensemble de table créé(s) (rattrapage, {} restant(es) après ce lot).",
                overviews.size(), Math.max(0, ids.size() - batchIds.size()));
        return overviews.size();
    }

    private static float[] deserializeVector(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) return null;
        try {
            return JSON.readValue(embeddingJson, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String serializeVector(float[] vector) {
        if (vector == null) return null;
        try {
            return JSON.writeValueAsString(vector);
        } catch (Exception e) {
            log.warn("Impossible de sérialiser un vecteur d'embedding", e);
            return null;
        }
    }

    @Override
    @Transactional
    public void deleteByTable(Long tableT24Id) {
        documentRepository.deleteByTableT24_Id(tableT24Id);
    }

    @Override
    public boolean hasMissingEmbeddings(Long tableT24Id) {
        return documentRepository.existsByTableT24_IdAndEmbeddingIsNull(tableT24Id);
    }

    private String buildChunkText(TableT24 table, FieldInfo field) {
        return "Module : " + table.getModule().getNomModule() + "\n"
                + "Table : " + table.getNomTable() + "\n"
                + "Colonne : " + field.name() + "\n"
                + "Type : " + field.dataType() + "\n"
                + "Obligatoire : " + field.mandatory() + "\n"
                + "Description : " + field.description();
    }

    /** Même format que {@link #buildChunkText}, mais pour la vue d'ensemble de la table (pas de colonne/type/
     *  obligatoire, puisqu'il ne s'agit pas d'un champ précis). */
    private String buildTableOverviewText(TableT24 table) {
        return "Module : " + table.getModule().getNomModule() + "\n"
                + "Table : " + table.getNomTable() + "\n"
                + "Description : " + table.getDescription();
    }

    /**
     * Parse le XML HelpText T24. Deux formats sont supportés :
     * - format "riche" (auto-fermant, avec attributs) : {@code <field name="X" dataType="Y" mandatory="YES" description="..."/>}
     * - format HelpText réel (le seul rencontré en pratique) : {@code <t><field>X</field><desc><p>...</p></desc></t>},
     *   qui ne fournit ni type ni caractère obligatoire explicite -> ces valeurs sont déduites du texte d'aide,
     *   sinon marquées "NON SPÉCIFIÉ".
     */
    private List<FieldInfo> parseFields(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) { }
                @Override public void error(SAXParseException e) { }
                @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            var xmlDoc = builder.parse(new InputSource(new StringReader(xml)));

            NodeList richFields = xmlDoc.getElementsByTagName("field");
            List<FieldInfo> fields = new ArrayList<>();
            boolean anyRich = false;
            for (int i = 0; i < richFields.getLength(); i++) {
                Element el = (Element) richFields.item(i);
                if (el.hasAttribute("name")) {
                    anyRich = true;
                    fields.add(new FieldInfo(
                            el.getAttribute("name").trim(),
                            el.hasAttribute("dataType") ? el.getAttribute("dataType") : "NON SPÉCIFIÉ",
                            toYesNo(el.getAttribute("mandatory")),
                            el.hasAttribute("description") ? el.getAttribute("description") : "Description non disponible."
                    ));
                }
            }
            if (anyRich) return fields;

            NodeList entries = xmlDoc.getElementsByTagName("t");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String name = textOfFirst(entry, "field");
                if (name == null || name.isBlank()) continue;

                String description = descriptionOf(entry);
                fields.add(new FieldInfo(name.trim(), guessDataType(description), guessMandatory(description), description));
            }
            return fields;
        } catch (Exception e) {
            log.warn("Impossible de parser le XML pour l'indexation", e);
            return List.of();
        }
    }

    private String toYesNo(String mandatory) {
        if (mandatory == null || mandatory.isBlank()) return "NON SPÉCIFIÉ";
        return "YES".equalsIgnoreCase(mandatory) ? "OUI" : "NON";
    }

    private String textOfFirst(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private String descriptionOf(Element entry) {
        NodeList descNodes = entry.getElementsByTagName("desc");
        if (descNodes.getLength() == 0) return "Description non disponible.";

        NodeList paragraphs = ((Element) descNodes.item(0)).getElementsByTagName("p");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            String line = paragraphs.item(i).getTextContent().trim().replaceAll("\\s+", " ");
            if (!line.isBlank()) lines.add(line);
        }
        return lines.isEmpty() ? "Description non disponible." : String.join("\n", lines);
    }

    /** Le HelpText T24 réel ne fournit pas de type de champ explicite : valeur déduite du texte d'aide, best-effort. */
    private String guessDataType(String description) {
        String d = description.toUpperCase();
        if (d.contains("DATE")) return "DATE";
        if (d.contains("AMOUNT") || d.contains("NUMERIC") || d.contains("NUMBER")) return "NUMERIC";
        return "NON SPÉCIFIÉ";
    }

    /**
     * Valeur de départ enregistrée en base au moment de l'import (voir {@link MandatoryClassifier} pour le détail
     * de la règle) : purement indicative, puisque ChatController/AnswerServiceImpl reclassent toujours dynamiquement
     * depuis la Description à l'usage plutôt que de faire confiance à cette valeur figée.
     */
    private String guessMandatory(String description) {
        return MandatoryClassifier.classify(description);
    }

    private record FieldInfo(String name, String dataType, String mandatory, String description) {}
}
