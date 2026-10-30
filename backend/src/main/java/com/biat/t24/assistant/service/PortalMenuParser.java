package com.biat.t24.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extrait un libellé humain par module T24 (ex. "AC" -> "Accounts") depuis le menu du portail T24
 * (portal-help-menu.xml). Ce fichier regroupe les tables T24 par catégorie produit lisible
 * ({@code <ts c="Accounts"><ts c="Generic">...<t><tar>AC/AC.ACCOUNT.LOCKED.CONCAT</tar></t>...}) :
 * la catégorie de premier niveau devient le libellé de tous les modules qu'elle référence.
 */

@Component
public class PortalMenuParser {

    private static final Logger log = LoggerFactory.getLogger(PortalMenuParser.class);

    /** Retourne Map&lt;code module, libellé&gt;. Map vide (jamais null) si le fichier est absent ou illisible. */
    public Map<String, String> parseModuleLabels(File portalMenuFile) {
        Map<String, String> labels = new HashMap<>();
        if (portalMenuFile == null || !portalMenuFile.isFile()) {
            log.warn("portal-help-menu.xml introuvable ({}) : les modules n'auront pas de libellé humain.",
                    portalMenuFile == null ? "chemin non résolu" : portalMenuFile.getAbsolutePath());
            return labels;
        }
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
            var doc = builder.parse(portalMenuFile);

            NodeList categoryMenus = doc.getElementsByTagName("categorymenu");
            if (categoryMenus.getLength() == 0) return labels;
            Element categoryMenu = (Element) categoryMenus.item(0);

            // La racine <ts c="Help by Product..."> est l'unique enfant <ts> direct de <categorymenu> ;
            // ses propres enfants <ts> directs sont les vraies catégories produit (ex. "Accounts", "Loans"...).
            for (Element root : directChildTs(categoryMenu)) {
                for (Element category : directChildTs(root)) {
                    String categoryName = category.getAttribute("c");
                    if (categoryName == null || categoryName.isBlank()) continue;
                    collectModuleCodes(category, categoryName.trim(), labels);
                }
            }
        } catch (Exception e) {
            log.warn("Impossible de parser portal-help-menu.xml : {}", e.getMessage());
        }
        log.info("{} module(s) avec libellé humain extrait(s) de portal-help-menu.xml", labels.size());
        return labels;
    }

    /** Premier libellé rencontré gagne si un même module apparaît sous plusieurs catégories (rare). */
    private void collectModuleCodes(Element node, String categoryName, Map<String, String> labels) {
        NodeList targets = node.getElementsByTagName("tar");
        for (int i = 0; i < targets.getLength(); i++) {
            String tar = targets.item(i).getTextContent();
            if (tar == null || tar.isBlank()) continue;
            int slash = tar.indexOf('/');
            String code = (slash > 0 ? tar.substring(0, slash) : tar).trim();
            if (code.isEmpty()) continue;
            labels.putIfAbsent(code, categoryName);
        }
    }

    private static List<Element> directChildTs(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "ts".equals(n.getNodeName())) {
                result.add((Element) n);
            }
        }
        return result;
    }
}
