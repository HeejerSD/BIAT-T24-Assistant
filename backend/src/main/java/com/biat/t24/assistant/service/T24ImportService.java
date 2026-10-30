package com.biat.t24.assistant.service;

import com.biat.t24.assistant.model.ModuleT24;
import com.biat.t24.assistant.model.TableT24;
import com.biat.t24.assistant.repository.ModuleT24Repository;
import com.biat.t24.assistant.repository.TableT24Repository;
import com.biat.t24.assistant.service.RAG_service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Importe les fichiers HelpText XML (disque) vers les tables MODULE_T24 / TABLE_T24 (Oracle). */
@Service
public class T24ImportService {

    private static final Logger log = LoggerFactory.getLogger(T24ImportService.class);

    @Value("${biat.t24.xml-directory}")
    private String xmlDirectory;

    private final ModuleT24Repository moduleRepository;
    private final TableT24Repository tableRepository;
    private final DocumentService documentService;
    private final PortalMenuParser portalMenuParser;

    public T24ImportService(ModuleT24Repository moduleRepository, TableT24Repository tableRepository,
                             DocumentService documentService, PortalMenuParser portalMenuParser) {
        this.moduleRepository = moduleRepository;
        this.tableRepository = tableRepository;
        this.documentService = documentService;
        this.portalMenuParser = portalMenuParser;
    }

    @Transactional
    public int importAll() {
        File dir = new File(xmlDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Répertoire XML introuvable : {}", dir.getAbsolutePath());
            return 0;
        }
        Path root = dir.toPath();

        List<File> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .map(Path::toFile)
                    .toList();
        } catch (IOException e) {
            log.error("Erreur parcours répertoire {}", root, e);
            return 0;
        }

        // portal-help-menu.xml est le frère direct du répertoire HelpText (.../HELP.TEXT/portal-help-menu.xml
        // à côté de .../HELP.TEXT/HelpText) : il fournit le libellé humain de chaque module (ex. AC -> "Accounts").
        File portalMenuFile = dir.getParentFile() != null ? new File(dir.getParentFile(), "portal-help-menu.xml") : null;
        Map<String, String> moduleLabels = portalMenuParser.parseModuleLabels(portalMenuFile);

        int count = 0;
        for (File file : files) {
            try {
                importFile(root, file, moduleLabels);
                count++;
            } catch (Exception e) {
                log.error("Erreur import {}", file.getName(), e);
            }
        }
        log.info("{} tables T24 importées en base", count);
        return count;
    }

    private void importFile(Path root, File file, Map<String, String> moduleLabels) throws IOException {
        Path parent = file.toPath().getParent();
        String nomModule = (parent != null && !parent.equals(root))
                ? parent.getFileName().toString()
                : "COMMON_FIELDS";

        ModuleT24 module = moduleRepository.findByNomModuleIgnoreCase(nomModule)
                .orElseGet(() -> {
                    ModuleT24 m = new ModuleT24();
                    m.setNomModule(nomModule);
                    return moduleRepository.save(m);
                });
        String label = moduleLabels.get(nomModule);
        if (label != null && !label.equals(module.getLabel())) {
            module.setLabel(label);
            module = moduleRepository.save(module);
        }

        String nomTable = file.getName().replaceAll("(?i)\\.xml$", "").toUpperCase();
        String xmlContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        TableT24 table = tableRepository.findByModuleAndNomTableIgnoreCase(module, nomTable)
                .orElseGet(TableT24::new);
        boolean isNew = table.getId() == null;
        boolean contentChanged = isNew || !xmlContent.equals(table.getData());

        table.setModule(module);
        table.setNomTable(nomTable);
        table.updateData(xmlContent);
        String description = extractTableDescription(xmlContent);
        table.setDescription(description);
        // La traduction française n'est PAS faite ici : elle ralentirait le reindex (un appel LLM séquentiel par
        // table). Elle est faite à la demande, en parallèle et mise en cache, par ChatController au premier
        // affichage de la liste des tables d'un module (voir ChatController.tablesByModule).
        table = tableRepository.save(table);

        if (!contentChanged) {
            // Fichier inchangé depuis le dernier import : on ne relance pas l'embedding (coûteux en appels réseau),
            // sauf si des chunks de cette table n'ont encore aucun vecteur (ex: service d'embedding indisponible
            // lors du dernier import) : dans ce cas on réindexe quand même pour les rattraper.
            if (documentService.hasMissingEmbeddings(table.getId())) {
                documentService.reindexTable(table);
            }
            return;
        }

        // Ré-indexe uniquement cette table : ses anciens chunks sont supprimés puis reconstruits,
        // les chunks des autres tables ne sont jamais touchés.
        documentService.reindexTable(table);
    }

    /** Extrait la description de la table elle-même depuis la section &lt;overview&gt;&lt;ovdesc&gt; du HelpText T24. */
    private String extractTableDescription(String xml) {
        if (xml == null || xml.isBlank()) return null;
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

            NodeList ovdesc = xmlDoc.getElementsByTagName("ovdesc");
            if (ovdesc.getLength() == 0) return null;

            NodeList paragraphs = ((Element) ovdesc.item(0)).getElementsByTagName("p");
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                String line = paragraphs.item(i).getTextContent().trim().replaceAll("\\s+", " ");
                if (!line.isBlank()) lines.add(line);
            }
            return lines.isEmpty() ? null : String.join(" ", lines);
        } catch (Exception e) {
            log.warn("Impossible d'extraire la description de la table depuis le XML", e);
            return null;
        }
    }
}
