package com.biat.t24.assistant.service.RAG_service;

import com.biat.t24.assistant.model.Document;
import com.biat.t24.assistant.model.ModuleT24;
import com.biat.t24.assistant.repository.DocumentRepository;
import com.biat.t24.assistant.repository.ModuleT24Repository;
import com.biat.t24.assistant.util.FuzzyMatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RetrieverServiceImpl implements RetrieverService {

    private static final Logger log = LoggerFactory.getLogger(RetrieverServiceImpl.class);
    private static final String GLOBAL_SCOPE = "__global__";

    /** Un nom de champ T24 : au moins deux mots alphanumériques séparés par des points (ex: CUSTOMER.ID). */
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+");

    /** Un simple mot (sans point), utilisé pour reconnaître un nom de colonne cité en langage naturel (ex: "description"). */
    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    /**
     * Mots grammaticaux français/anglais courants (articles, prépositions, pronoms, interrogatifs, verbes usuels)
     * qui ne sont jamais des noms de colonnes T24 plausibles : exclus de la correspondance mot-à-mot à l'échelle
     * du module (WORD_PATTERN, insensible à la casse) pour éviter qu'un mot ordinaire de la phrase ("dans",
     * "quelle", "existe"...) ne coïncide par hasard avec une colonne technique quelque part parmi les centaines
     * de tables d'un module. Ce filtre ne s'applique pas à l'échelle d'une seule table (scope bien plus restreint,
     * où ce risque de collision est négligeable).
     */
    private static final Set<String> MODULE_SCOPE_STOPWORDS = Set.of(
            "le", "la", "les", "un", "une", "des", "de", "du", "et", "ou", "est", "sont", "dans", "sur", "pour",
            "avec", "sans", "quel", "quelle", "quels", "quelles", "quoi", "que", "qui", "existe", "table", "champ",
            "colonne", "module", "obligatoire", "optionnel", "sert", "the", "and", "for", "with", "what", "which",
            "field", "column", "does");

    /** Nombre maximal de mots de contenu (après filtrage des mots grammaticaux, voir MODULE_SCOPE_STOPWORDS) au-delà
     *  duquel la priorité "nom de table exact" (voir addTableOverviewMatch) n'est plus considérée fiable : une
     *  question courte et directe ("à quoi sert ACCOUNT ?") n'a qu'un seul mot de contenu, donc ce mot EST le
     *  sujet réel de la question ; une question plus longue et descriptive peut en revanche contenir un mot
     *  coïncidant par hasard avec un nom de table sans être la vraie table visée. */
    private static final int TABLE_PRIORITY_MAX_CONTENT_WORDS = 2;

    /** Le mot "champ"/"colonne" (ou l'anglais "field"/"column") : voir son usage dans {@link
     *  #findExactColumnMatches} pour désambiguïser un mot qui coïncide à la fois avec un nom de colonne et un
     *  vrai nom de table (ex: CURRENCY). */
    private static final Pattern FIELD_WORD_PATTERN = Pattern.compile("(?iu)\\b(champ|colonne|field|column)s?\\b");

    /** Écart maximal (en caractères) toléré entre "champ"/"colonne" et le mot qui le suit pour le considérer
     *  comme explicitement désigné comme un champ (voir {@link #FIELD_WORD_PATTERN}). */
    private static final int FIELD_WORD_PROXIMITY = 20;

    private final DocumentRepository documentRepository;
    private final EmbeddingService embeddingService;
    private final FaissClient faissClient;
    private final ModuleT24Repository moduleRepository;

    public RetrieverServiceImpl(DocumentRepository documentRepository, EmbeddingService embeddingService,
                                 FaissClient faissClient, ModuleT24Repository moduleRepository) {
        this.documentRepository = documentRepository;
        this.embeddingService = embeddingService;
        this.faissClient = faissClient;
        this.moduleRepository = moduleRepository;
    }

    private static final String NO_HELP_TEXT = "Help Text for this field is unavailable";

    /** Longueur (en caractères) de vue d'ensemble au-delà de laquelle le bonus de richesse (voir
     *  {@link #searchModuleOverview}) est déjà à son maximum. */
    private static final int OVERVIEW_RICH_CONTENT_CHARS = 400;
    /** Poids maximal de ce bonus, volontairement faible face à un vrai écart de cosinus (généralement > 0.1-0.2
     *  entre une table sur le thème demandé et une table sans rapport). */
    private static final double OVERVIEW_RICH_CONTENT_WEIGHT = 0.08;
    /** Bonus aux tables dont le nom commence par le préfixe du module (ex. "EB.") — voir {@link
     *  #searchModuleOverview}. Volontairement fort (garantit quasi toujours la priorité) : une table sans ce
     *  préfixe n'est pas forcément mal indexée (T24 documente légitimement certaines tables utilitaires
     *  partagées sous plusieurs modules), mais reste plus incertaine à présenter comme LE contenu du module. */
    private static final double OVERVIEW_PREFIX_MATCH_BONUS = 0.5;
    /** En dessous de cette longueur de contenu, une vue d'ensemble est trop mince pour justifier autre chose
     *  qu'une paraphrase creuse (voir {@link #searchModuleOverview}) : filtrée en priorité, sauf si elle est
     *  nécessaire pour atteindre {@code topK} faute d'assez d'autres candidats. */
    private static final int OVERVIEW_MIN_CONTENT_CHARS = 150;

    @Override
    public List<ScoredDocument> searchModuleOverview(String question, String module, int topK,
                                                      Set<String> excludeTables) {
        String mod = module.trim();
        List<ScoredDocument> out = new ArrayList<>();
        out.add(new ScoredDocument(moduleSummary(mod), 1.0));

        String prefix = mod.toUpperCase() + ".";
        List<Document> overviews = documentRepository
                .findByModuleIgnoreCaseAndColumnName(mod, Document.TABLE_OVERVIEW_COLUMN_MARKER).stream()
                .filter(d -> excludeTables == null || !excludeTables.contains(d.getTableName().toUpperCase()))
                .toList();
        float[] q = embeddingService.embedBatch(List.of(question)).get(0);
        List<ScoredDocument> ranked = new ArrayList<>();
        for (Document d : overviews) {
            double cosine = q == null ? 0.0 : cosineSimilarity(q, deserializeVector(d.getEmbedding()));
            // Léger bonus aux vues d'ensemble les plus détaillées : pour une question générique sur le module
            // (aucun vrai signal thématique dans le texte de la question), l'écart de cosinus entre tables est
            // surtout du bruit — sans ce bonus, une table utilitaire au ovdesc très court pouvait l'emporter par
            // hasard sur une table bien plus représentative, ne laissant au LLM qu'un texte source trop mince
            // pour écrire autre chose qu'une paraphrase creuse. Volontairement faible (0.08 max) pour ne jamais
            // dominer un vrai écart thématique (question TOPIC_TABLES, filtrage par thème).
            double lengthBonus = Math.min(d.getContent().length(), OVERVIEW_RICH_CONTENT_CHARS)
                    / (double) OVERVIEW_RICH_CONTENT_CHARS * OVERVIEW_RICH_CONTENT_WEIGHT;
            double prefixBonus = d.getTableName().toUpperCase().startsWith(prefix) ? OVERVIEW_PREFIX_MATCH_BONUS : 0.0;
            ranked.add(new ScoredDocument(d, cosine + lengthBonus + prefixBonus));
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        // Écarte d'abord les vues d'ensemble trop minces pour être présentées seules comme "le contenu du
        // module" ; seulement si cela ne laisse pas assez de candidats, on réintègre les meilleures d'entre
        // elles (mieux qu'un résultat plus court que demandé).
        List<ScoredDocument> rich = ranked.stream().filter(r -> r.document().getContent().length() >= OVERVIEW_MIN_CONTENT_CHARS).toList();
        List<ScoredDocument> pool = rich.size() >= topK ? rich : ranked;
        for (ScoredDocument r : pool) {
            if (out.size() > topK) break;
            out.add(r);
        }
        return out;
    }

    /** Document de synthèse d'un module : libellé officiel, nombre et liste des tables (voir Document#MODULE_SUMMARY_COLUMN_MARKER). */
    private Document moduleSummary(String module) {
        String label = moduleRepository.findByNomModuleIgnoreCase(module).map(ModuleT24::getLabel).orElse(null);
        List<String> tables = documentRepository.findDistinctTableNamesByModuleIgnoreCase(module).stream()
                .sorted().toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Module : ").append(module.toUpperCase()).append('\n');
        if (label != null && !label.isBlank()) sb.append("Libellé officiel du module : ").append(label).append('\n');
        sb.append("Nombre de tables documentées : ").append(tables.size()).append('\n');
        // Les noms de tables ne sont volontairement PAS listés ici : donnés en bloc, le modèle local les recopie tous
        // (plusieurs dizaines de puces) au lieu de présenter le module ; seules les vues d'ensemble des tables
        // les plus pertinentes, ajoutées après cette synthèse, servent d'exemples.
        sb.append("Les tables décrites ci-dessous ne sont qu'un échantillon des tables du module.");
        Document d = new Document();
        d.setModule(module.toUpperCase());
        d.setTableName(module.toUpperCase());
        d.setColumnName(Document.MODULE_SUMMARY_COLUMN_MARKER);
        d.setContent(sb.toString());
        return d;
    }

    @Override
    public List<ScoredDocument> documentsOfTables(List<String> tableNames, int perTable) {
        List<ScoredDocument> out = new ArrayList<>();
        for (String table : tableNames) {
            List<Document> docs = documentRepository.findByTableNameIgnoreCase(table);
            docs.stream().filter(d -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                    .findFirst().ifPresent(d -> out.add(new ScoredDocument(d, 1.0)));
            docs.stream()
                    .filter(d -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                    .filter(d -> !d.getContent().contains(NO_HELP_TEXT))
                    .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                    .limit(perTable)
                    .forEach(d -> out.add(new ScoredDocument(d, 1.0)));
        }
        return out;
    }

    @Override
    public List<ScoredDocument> validationDocuments(List<String> tableNames, int limit) {
        List<ScoredDocument> out = new ArrayList<>();
        for (String table : tableNames) {
            documentRepository.findByTableNameIgnoreCase(table).stream()
                    .filter(d -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                    .filter(d -> {
                        int i = d.getContent().indexOf("Validation Rules");
                        return i >= 0 && d.getContent().length() - i > 45;
                    })
                    .sorted((a, b) -> Integer.compare(b.getContent().length(), a.getContent().length()))
                    .limit(2)
                    .forEach(d -> out.add(new ScoredDocument(d, 1.0)));
            if (out.size() >= limit) break;
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Un exemple documenté dans la Description : "Example 1:", "Example:", "for example", "e.g.". */
    private static final Pattern DOCUMENTED_EXAMPLE = Pattern.compile("(?i)\\bexample\\s*\\d*\\s*:|for example|\\be\\.g\\.");

    @Override
    public List<ScoredDocument> exampleDocuments(List<String> tableNames, int limit) {
        List<ScoredDocument> out = new ArrayList<>();
        for (String table : tableNames) {
            documentRepository.findByTableNameIgnoreCase(table).stream()
                    .filter(d -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                    .filter(d -> DOCUMENTED_EXAMPLE.matcher(d.getContent()).find())
                    .sorted((a, b) -> Integer.compare(a.getContent().length(), b.getContent().length()))
                    .limit(2)
                    .forEach(d -> out.add(new ScoredDocument(focusOnExample(d), 1.0)));
            if (out.size() >= limit) break;
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static final int EXAMPLE_INTRO_CHARS = 450;
    private static final int EXAMPLE_MAX_CHARS = 1200;

    /**
     * Copie du document réduite à l'essentiel pour illustrer par un exemple : en-tête, début de la Description,
     * puis le passage de l'exemple lui-même (jusqu'aux règles de validation) — le modèle local restitue bien
     * mieux un exemple court que le même exemple noyé dans une Description de plusieurs milliers de caractères.
     */
    private static Document focusOnExample(Document d) {
        String content = d.getContent();
        Matcher m = DOCUMENTED_EXAMPLE.matcher(content);
        int descStart = content.indexOf("Description : ");
        if (!m.find() || descStart < 0 || m.start() < descStart + EXAMPLE_INTRO_CHARS) return d;
        int end = content.indexOf("Validation Rules", m.start());
        if (end < 0 || end - m.start() > EXAMPLE_MAX_CHARS) end = Math.min(content.length(), m.start() + EXAMPLE_MAX_CHARS);
        Document copy = new Document();
        copy.setModule(d.getModule());
        copy.setTableName(d.getTableName());
        copy.setColumnName(d.getColumnName());
        copy.setContent(content.substring(0, descStart + EXAMPLE_INTRO_CHARS).stripTrailing() + " [...] "
                + content.substring(m.start(), end).strip());
        return copy;
    }

    @Override
    public List<ScoredDocument> search(String question, String module, String tableName, int topK) {
        List<ScoredDocument> exactMatches = findExactColumnMatches(question, module, tableName);
        if (!exactMatches.isEmpty()) {
            // Un nom de champ précis a été reconnu : on ne renvoie que ce(s) champ(s), sans bruit sémantique.
            return exactMatches.size() > topK ? exactMatches.subList(0, topK) : exactMatches;
        }

        float[] queryVector = embeddingService.embedBatch(List.of(question)).get(0);
        if (queryVector == null) {
            log.warn("Impossible de vectoriser la question, recherche annulée : {}", question);
            return List.of();
        }

        String scope;
        boolean isTableScope = tableName != null && !tableName.isBlank();
        if (isTableScope) {
            scope = tableName.trim();
        } else if (module != null && !module.isBlank()) {
            scope = module.trim();
        } else {
            scope = GLOBAL_SCOPE;
        }

        try {
            List<ScoredDocument> results = searchViaFaiss(question, queryVector, scope, isTableScope, topK);
            // Question sur la table dans son ensemble ("explique la table X") sans champ précis nommé : la vue
            // d'ensemble de la table (déjà indexée) est la vraie source de la réponse, pas 5 champs quelconques
            // les plus proches de la formulation.
            if (isTableScope && TABLE_LEVEL_QUESTION_PATTERN.matcher(question).find()) {
                return withTableOverviewFirst(results, scope, topK);
            }
            return results;
        } catch (Exception e) {
            log.error("Recherche FAISS indisponible (scope={}), aucun résultat retourné : {}", scope, e.getMessage());
            return List.of();
        }
    }

    /** Formulations demandant de présenter/expliquer une TABLE entière (voir {@link #search}). */
    private static final Pattern TABLE_LEVEL_QUESTION_PATTERN = Pattern.compile(
            "(?iu)\\b(explique|expliquer|d[ée]cri[sv]|parle|pr[ée]sente|[àa] quoi sert|c'est quoi|de quoi s'agit|"
                    + "que contient|r[ôo]le)");

    private List<ScoredDocument> withTableOverviewFirst(List<ScoredDocument> results, String tableName, int topK) {
        List<ScoredDocument> out = new ArrayList<>();
        documentRepository.findByTableNameIgnoreCase(tableName).stream()
                .filter(d -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                .findFirst()
                .ifPresent(overview -> out.add(new ScoredDocument(overview, 1.0)));
        for (ScoredDocument r : results) {
            if (out.size() >= topK) break;
            if (!Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(r.document().getColumnName())) out.add(r);
        }
        return out;
    }

    /**
     * Si la question nomme un champ T24 précis, le retrouve exactement en base avant tout calcul vectoriel :
     * soit au format pointé complet (ex: CUSTOMER.ID), soit, quand une table précise est sélectionnée (dialogue
     * guidé), un simple mot du langage naturel correspondant exactement à une colonne de cette table
     * (ex: "description" -> DESCRIPTION), pour éviter de renvoyer du bruit sémantique sur une question sans ambiguïté.
     */
    private List<ScoredDocument> findExactColumnMatches(String question, String module, String tableName) {
        List<ScoredDocument> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher dotted = FIELD_NAME_PATTERN.matcher(question);
        List<String> dottedTokens = new ArrayList<>();
        // Les noms pointés sont traités ici en entier : leurs fragments ne doivent pas être re-cherchés comme
        // mots isolés plus bas (ex. "account" dans "account.title.1" remontait toutes les colonnes ACCOUNT).
        StringBuilder maskedQuestion = new StringBuilder(question);
        while (dotted.find()) {
            String token = dotted.group();
            dottedTokens.add(token);
            addExactMatches(token, module, tableName, matches, seen);
            for (int i = dotted.start(); i < dotted.end(); i++) maskedQuestion.setCharAt(i, ' ');
        }
        String wordSource = maskedQuestion.toString();

        // Mots simples (sans point) candidats à la reconnaissance floue si rien d'exact n'est trouvé (voir plus
        // bas) — ex. "acount"/"currncy" dans "le champ currncy dans acount", aucun des deux n'étant pointé. Un nom
        // de champ T24 fait toujours au moins 3 caractères, jamais un mot grammatical courant.
        List<String> plainCandidates = new ArrayList<>();

        if (tableName != null && !tableName.isBlank()) {
            Matcher word = WORD_PATTERN.matcher(wordSource);
            while (word.find()) {
                String candidate = word.group();
                if (candidate.length() < 3 || seen.contains(candidate.toUpperCase())) continue;
                plainCandidates.add(candidate);
                List<Document> docs = documentRepository.findByColumnNameIgnoreCaseAndTableNameIgnoreCase(candidate, tableName.trim());
                docs.forEach(doc -> matches.add(new ScoredDocument(doc, 1.0)));
                if (!docs.isEmpty()) seen.add(candidate.toUpperCase());
            }
        } else if (module != null && !module.isBlank()) {
            // Aucune table précise sélectionnée : on exclut les mots grammaticaux courants (voir
            // MODULE_SCOPE_STOPWORDS) avant de tenter la correspondance mot-à-mot, pour rester précis malgré les
            // centaines de tables du module.
            Matcher word = WORD_PATTERN.matcher(wordSource);
            List<String> candidates = new ArrayList<>();
            List<Integer> candidateStarts = new ArrayList<>();
            while (word.find()) {
                String candidate = word.group();
                if (candidate.length() < 3 || MODULE_SCOPE_STOPWORDS.contains(candidate.toLowerCase())) continue;
                candidates.add(candidate);
                candidateStarts.add(word.start());
            }
            plainCandidates.addAll(candidates);

            // Un mot peut coïncider avec un vrai nom de table alors qu'il est explicitement désigné comme un
            // CHAMP par la question (ex: "CURRENCY" est à la fois la colonne ACCOUNT.CURRENCY et une vraie table
            // de référence des devises) : "champ"/"colonne" juste avant ce mot signale sans ambiguïté qu'il s'agit
            // d'un champ, jamais d'une table — observé en pratique, "ce champ, il se trouve dans quelle table
            // exactement ?" (reformulé en "le champ CURRENCY...") répondait à tort avec la table CURRENCY entière
            // au lieu de désigner la table ACCOUNT qui contient ce champ.
            List<Integer> fieldWordEnds = new ArrayList<>();
            Matcher fw = FIELD_WORD_PATTERN.matcher(question);
            while (fw.find()) fieldWordEnds.add(fw.end());

            // La priorité "nom de table exact" (voir addTableOverviewMatch) n'est un signal fiable QUE si ce mot
            // est essentiellement tout le contenu réel de la question (ex. "à quoi sert ACCOUNT ?" : un seul mot
            // de contenu après filtrage) : dans une question plus longue et descriptive, un mot ordinaire peut
            // coïncider par hasard avec un nom de table sans être la vraie table visée (observé en pratique :
            // "quelle table gère les versions d'écran de la Home Page Teller ?" matchait à tort la table TELLER
            // elle-même — la vraie réponse est TT.TELLER.HP.VERSIONS, "Teller" n'étant ici qu'un mot au sein d'une
            // expression plus longue "Home Page Teller", pas une référence directe à la table).
            boolean allowTablePriority = candidates.size() <= TABLE_PRIORITY_MAX_CONTENT_WORDS;
            for (int i = 0; i < candidates.size(); i++) {
                String candidate = candidates.get(i);
                if (seen.contains(candidate.toUpperCase())) continue;
                int start = candidateStarts.get(i);
                boolean explicitlyNamedAsField = fieldWordEnds.stream()
                        .anyMatch(end -> start >= end && start - end <= FIELD_WORD_PROXIMITY);
                if (allowTablePriority && !explicitlyNamedAsField && addTableOverviewMatch(candidate, matches, seen)) continue;
                List<Document> docs = documentRepository.findByColumnNameIgnoreCaseAndModuleIgnoreCase(candidate, module.trim());
                docs.forEach(doc -> matches.add(new ScoredDocument(doc, 1.0)));
                if (!docs.isEmpty()) seen.add(candidate.toUpperCase());
            }
        }

        // Repli tolérant aux fautes de frappe (ex: "MIN.COM.AMT.LCCY" pour la vraie colonne "MIN.COMM.AMT.LCCY",
        // ou un simple mot mal orthographié comme "acount"/"currncy") : uniquement si rien d'exact n'a été
        // trouvé, pour ne jamais préférer une approximation à une vraie correspondance déjà exacte. Le texte
        // original de la question n'est jamais modifié : le LLM voit à la fois la faute de frappe (question) et
        // le nom réel corrigé (contexte retrouvé), ce qui lui permet de formuler une réponse du type "Si vous
        // vouliez dire X, ...".
        // Mots simples : au moins 6 lettres et jamais un mot courant — avec la tolérance minimale de 2 fautes, un
        // mot court ("les", "table", "dans") se rapprochait à tort d'un nom de colonne comme TDS, NAME ou VALUE et
        // court-circuitait la vraie recherche avec un résultat sans rapport.
        List<String> fuzzyCandidates = new ArrayList<>(dottedTokens);
        plainCandidates.stream()
                .filter(w -> w.length() >= 6 && !MODULE_SCOPE_STOPWORDS.contains(w.toLowerCase()))
                .forEach(fuzzyCandidates::add);
        if (matches.isEmpty() && !fuzzyCandidates.isEmpty()) {
            addFuzzyColumnMatches(fuzzyCandidates, module, tableName, matches, seen);
        }
        return matches;
    }

    private void addFuzzyColumnMatches(List<String> candidates, String module, String tableName,
                                        List<ScoredDocument> matches, Set<String> seen) {
        List<String> knownColumns = tableName != null && !tableName.isBlank()
                ? documentRepository.findDistinctColumnNamesByTableNameIgnoreCase(tableName.trim())
                : module != null && !module.isBlank()
                        ? documentRepository.findDistinctColumnNamesByModuleIgnoreCase(module.trim())
                        : List.of();
        // Un mot simple mal orthographié peut aussi désigner une TABLE (ex: "acount" -> "ACCOUNT"), pas seulement
        // une colonne — uniquement à l'échelle du module, jamais quand une table précise est déjà sélectionnée
        // (le nom de cette table est alors déjà connu, pas besoin de le deviner).
        List<String> knownTables = tableName == null || tableName.isBlank()
                ? (module != null && !module.isBlank()
                        ? documentRepository.findDistinctTableNamesByModuleIgnoreCase(module.trim())
                        : List.of())
                : List.of();
        for (String candidate : candidates) {
            if (seen.contains(candidate.toUpperCase())) continue;
            boolean matched = false;
            for (String known : knownColumns) {
                if (FuzzyMatch.isCloseEnough(candidate, known)) {
                    addExactMatches(known, module, tableName, matches, seen);
                    matched = true;
                    break;
                }
            }
            if (matched) continue;
            for (String known : knownTables) {
                if (FuzzyMatch.isCloseEnough(candidate, known) && addTableOverviewMatch(known, matches, seen)) break;
            }
        }
    }

    /**
     * Si {@code candidate} correspond exactement au nom d'une vraie table T24, ajoute sa vue d'ensemble déjà
     * indexée (voir {@code Document.TABLE_OVERVIEW_COLUMN_MARKER}) aux résultats et retourne {@code true} —
     * signal prioritaire sur une simple colonne homonyme d'une autre table, qui ne doit alors plus être cherchée
     * pour ce même mot (même si la table n'a pas encore de vue d'ensemble indexée : mieux vaut ne rien montrer
     * pour ce mot que d'afficher une colonne sans rapport).
     */
    private boolean addTableOverviewMatch(String candidate, List<ScoredDocument> matches, Set<String> seen) {
        if (seen.contains(candidate.toUpperCase())) return false;
        List<Document> tableDocs = documentRepository.findByTableNameIgnoreCase(candidate);
        if (tableDocs.isEmpty()) return false;
        seen.add(candidate.toUpperCase());
        tableDocs.stream()
                .filter(d -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                .findFirst()
                .ifPresent(overview -> matches.add(new ScoredDocument(overview, 1.0)));
        return true;
    }

    private void addExactMatches(String candidate, String module, String tableName, List<ScoredDocument> matches, Set<String> seen) {
        if (seen.contains(candidate.toUpperCase())) return;
        List<Document> docs;
        if (tableName != null && !tableName.isBlank()) {
            docs = documentRepository.findByColumnNameIgnoreCaseAndTableNameIgnoreCase(candidate, tableName.trim());
        } else if (module != null && !module.isBlank()) {
            docs = documentRepository.findByColumnNameIgnoreCaseAndModuleIgnoreCase(candidate, module.trim());
        } else {
            docs = documentRepository.findByColumnNameIgnoreCase(candidate);
        }
        docs.forEach(doc -> matches.add(new ScoredDocument(doc, 1.0)));
        if (!docs.isEmpty()) seen.add(candidate.toUpperCase());
    }

    /** Nombre de candidats FAISS présélectionnés avant reranking : plus large que topK pour laisser au
     *  cross-encoder de vraies alternatives à départager (sinon reranker topK candidats revient à ne faire que
     *  les réordonner entre eux, sans jamais repêcher un bon résultat classé juste après par la seule similarité
     *  cosinus). */
    private static final int RERANK_CANDIDATE_POOL_MULTIPLIER = 4;
    private static final int RERANK_CANDIDATE_POOL_MIN = 15;

    private List<ScoredDocument> searchViaFaiss(String question, float[] queryVector, String scope,
                                                  boolean isTableScope, int topK) {
        int candidatePoolSize = Math.max(topK * RERANK_CANDIDATE_POOL_MULTIPLIER, RERANK_CANDIDATE_POOL_MIN);

        List<FaissClient.ScoredId> denseHits = faissClient.search(scope, queryVector, candidatePoolSize);
        Map<Long, Double> denseScores = denseHits.stream()
                .collect(Collectors.toMap(FaissClient.ScoredId::id, FaissClient.ScoredId::score));

        // Recherche hybride : complète les candidats denses (embeddings) par une correspondance BM25 (mots
        // exacts) sur le même scope, utile pour des termes rares/techniques qu'un embedding généraliste
        // représente mal — un candidat trouvé seulement par BM25 peut ainsi être repêché même si la recherche
        // dense l'aurait manqué. Ignorée à l'échelle globale (aucun module/table sélectionné) : charger tout le
        // corpus serait trop coûteux pour ce scope déjà traité en dégradé (topK=1).
        Set<Long> candidateIds = new LinkedHashSet<>(denseScores.keySet());
        if (!GLOBAL_SCOPE.equals(scope)) {
            List<Document> scopeCorpus = isTableScope
                    ? documentRepository.findByTableNameIgnoreCase(scope)
                    : documentRepository.findByModuleIgnoreCase(scope);
            candidateIds.addAll(Bm25Scorer.topN(question, scopeCorpus, candidatePoolSize));
        }
        if (candidateIds.isEmpty()) return List.of();

        Map<Long, Document> byId = documentRepository.findAllById(candidateIds)
                .stream().collect(Collectors.toMap(Document::getId, d -> d));

        List<ScoredDocument> candidates = new ArrayList<>();
        for (Long id : candidateIds) {
            Document doc = byId.get(id);
            if (doc == null) continue;
            // Un candidat trouvé uniquement par BM25 n'a pas de score cosinus FAISS : on le calcule localement
            // (même embedding source que FAISS, donc directement comparable) plutôt que de lui attribuer un
            // score arbitraire, pour que le seuil de confiance de ChatController reste valide sur ce candidat
            // aussi bien que sur les autres.
            Double score = denseScores.get(id);
            candidates.add(new ScoredDocument(doc, score != null ? score : cosineSimilarity(queryVector, deserializeVector(doc.getEmbedding()))));
        }
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));

        return rerank(question, candidates, topK);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static float[] deserializeVector(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) return null;
        try {
            return JSON.readValue(embeddingJson, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Réordonne les candidats FAISS via un cross-encoder (question, contenu du chunk), plus précis que la seule
     * similarité cosinus qui compare des embeddings calculés indépendamment de la question. Conserve le score
     * cosinus d'origine sur chaque {@link ScoredDocument} (seul l'ORDRE/la sélection change) : le seuil de
     * confiance de ChatController continue donc de raisonner sur la même échelle de score qu'avant, jamais sur
     * le score brut du cross-encoder (non borné, non comparable à un seuil calibré pour du cosinus). Best-effort :
     * si le service de reranking échoue, on retombe simplement sur l'ordre FAISS d'origine.
     */
    private List<ScoredDocument> rerank(String question, List<ScoredDocument> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        try {
            List<FaissClient.RerankCandidate> items = candidates.stream()
                    .map(c -> new FaissClient.RerankCandidate(c.document().getId(), c.document().getContent()))
                    .toList();
            List<FaissClient.ScoredId> reranked = faissClient.rerank(question, items);
            if (reranked.isEmpty()) return candidates.subList(0, topK);

            Map<Long, ScoredDocument> byId = candidates.stream()
                    .collect(Collectors.toMap(c -> c.document().getId(), c -> c));
            List<ScoredDocument> ordered = reranked.stream()
                    .map(r -> byId.get(r.id()))
                    .filter(Objects::nonNull)
                    .limit(topK)
                    .toList();
            return ordered.isEmpty() ? candidates.subList(0, topK) : ordered;
        } catch (Exception e) {
            log.warn("Reranking indisponible, conservation de l'ordre FAISS d'origine : {}", e.getMessage());
            return candidates.subList(0, topK);
        }
    }
}
