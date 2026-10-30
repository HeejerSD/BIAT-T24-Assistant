package com.biat.t24.assistant.controller;

import com.biat.t24.assistant.config.ActiveModules;
import com.biat.t24.assistant.dto.ChatHistoryDto;
import com.biat.t24.assistant.dto.ChatRequestDto;
import com.biat.t24.assistant.dto.ChatResponseDto;
import com.biat.t24.assistant.dto.ChatStepDto;
import com.biat.t24.assistant.dto.ColumnDescriptionDto;
import com.biat.t24.assistant.dto.ColumnResultDto;
import com.biat.t24.assistant.dto.ChatSummaryDto;
import com.biat.t24.assistant.dto.ModuleMatchDto;
import com.biat.t24.assistant.dto.ModuleSummaryDto;
import com.biat.t24.assistant.dto.SourceFieldDto;
import com.biat.t24.assistant.dto.TableColumnsDto;
import com.biat.t24.assistant.dto.TableSummaryDto;
import com.biat.t24.assistant.model.ChatHistory;
import com.biat.t24.assistant.model.Chat;
import com.biat.t24.assistant.model.Document;
import com.biat.t24.assistant.model.ModuleT24;
import com.biat.t24.assistant.model.TableT24;
import com.biat.t24.assistant.repository.ChatHistoryRepository;
import com.biat.t24.assistant.repository.ChatRepository;
import com.biat.t24.assistant.repository.DocumentRepository;
import com.biat.t24.assistant.repository.ModuleT24Repository;
import com.biat.t24.assistant.repository.TableT24Repository;
import com.biat.t24.assistant.service.T24ImportService;
import com.biat.t24.assistant.service.RAG_service.AnswerService;
import com.biat.t24.assistant.service.RAG_service.DocumentService;
import com.biat.t24.assistant.service.RAG_service.MandatoryClassifier;
import com.biat.t24.assistant.service.RAG_service.QuestionIntent;
import com.biat.t24.assistant.service.RAG_service.RetrieverService;
import com.biat.t24.assistant.util.FuzzyMatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Endpoints consommés par le frontend (bouton "Charger les données" et tableau de bord). */
@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "http://localhost:4200")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Sérialisation des colonnes sources d'une réponse (voir {@link SourceFieldDto}), pour persistance dans
     *  l'historique et restitution après un rafraîchissement de page. */
    private static final ObjectMapper SOURCE_FIELDS_JSON = new ObjectMapper();

    /** En dessous de ce score de similarité, on considère qu'aucun résultat n'est réellement pertinent (évite d'appeler le LLM/d'halluciner sur des questions hors-sujet).
     *  Volontairement permissif : les questions reformulées ou descriptives (sans mentionner le nom exact de la colonne) ont un score
     *  sémantique plus faible qu'une correspondance exacte, mais restent pertinentes. Le LLM (system prompt) reste chargé de dire
     *  explicitement quand le contexte ne permet pas de répondre avec certitude. */
    private static final double CONFIDENCE_THRESHOLD = 0.30;

    /** Quand une table précise est déjà sélectionnée, le "bruit" possible est limité à sa vingtaine de colonnes :
     *  on peut se permettre d'être plus tolérant qu'en recherche module/globale pour laisser passer les questions
     *  reformulées (ex. "où saisir le message d'erreur ?" pour ERR.MESSAGE, score ~0.26). */
    private static final double TABLE_SCOPED_CONFIDENCE_THRESHOLD = 0.20;

    /** Score renvoyé uniquement par une correspondance exacte (RetrieverServiceImpl#findExactColumnMatches),
     *  jamais par la similarité cosinus FAISS en pratique : sert à distinguer une réponse certaine d'un simple
     *  score sémantique qui aurait pu dépasser le seuil de confiance par coïncidence sur une question vague. */
    private static final double EXACT_MATCH_SCORE = 0.999;

    /** Demande explicite de la liste des tables d'un module : question structurelle (énumération), pas une question
     *  sur un champ précis — la recherche sémantique par colonne n'a aucun sens ici, et le LLM inventerait des noms
     *  de table plausibles mais faux. On répond directement avec les vraies tables de la base, sans LLM. */
    private static final java.util.regex.Pattern LIST_TABLES_PATTERN = java.util.regex.Pattern.compile(
            "(liste|listez|[ée]num[èe]re[zr]?|cite[zr]?|donne[- ]moi|montre[- ]moi|affiche[zr]?|quel(?:le)?s? sont)"
                    + ".{0,20}(toutes )?les tables",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** Filtre thématique ou détail par table demandé en plus de l'énumération (rôle, champs principaux, "qui
     *  contiennent X", "pour chacune"...) : dans ce cas {@link #LIST_TABLES_PATTERN} ne doit PAS déclencher le
     *  court-circuit d'énumération brute — la question appelle une vraie recherche + synthèse (voir
     *  QuestionIntent#TOPIC_TABLES), sinon le reste de la question est purement ignoré (observé en pratique :
     *  "quelles sont les tables ... qui contiennent des informations sur les contrats de crédit ? Pour chacune,
     *  précise son rôle et les champs principaux." renvoyait la liste BRUTE de toutes les tables du module, sans
     *  tenir compte ni du filtre thématique ni de la demande de détail). */
    private static final java.util.regex.Pattern LIST_DETAIL_QUALIFIER_PATTERN = java.util.regex.Pattern.compile(
            "(?iu)r[ôo]le|champs?|principaux?|principales?|chacune|chaque\\b|"
                    + "qui\\s+(contiennent|g[èe]rent|traitent|portent|concernent|stockent)|"
                    + "informations?\\s+sur|concernant|li[ée]es?\\s+[àa]|relatives?\\s+[àa]|structure|description");

    /** Vrai seulement pour une demande d'ÉNUMÉRATION BRUTE de toutes les tables d'un module ("liste les tables du
     *  module LD"), sans filtre thématique ni détail demandé (voir {@link #LIST_DETAIL_QUALIFIER_PATTERN}) : dans
     *  ce cas précis, une réponse directe (vraies tables de la base, sans LLM) est fiable et plus rapide. */
    private static boolean isSimpleListAllTablesRequest(String question) {
        return LIST_TABLES_PATTERN.matcher(question).find()
                && !LIST_DETAIL_QUALIFIER_PATTERN.matcher(question).find();
    }

    /** Question générale sur le module/la table dans son ensemble, ou visant à IDENTIFIER une table plutôt qu'à
     *  se renseigner sur un champ précis (ex. "quelle table gère/définit/contient X ?") : dans ce cas, les chunks
     *  retrouvés servent uniquement de contexte pour la synthèse du LLM — la réponse ne cite jamais un champ
     *  précis, donc la carte "Voir la source" ne doit afficher que la table identifiée, pas une liste de colonnes
     *  qui donnerait l'impression trompeuse que ces champs précis sont "la réponse" (voir buildTableOnlySourceFields). */
    private static final java.util.regex.Pattern OVERVIEW_QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "(à quoi sert (ce|cette|le|la)|que fait (ce|cette|le|la)|\\bd[ée]cri[sv]|\\bexplique|\\br[ée]sum|\\bpr[ée]sente|"
                    + "de quoi s'agit|vue d'ensemble|en quoi consiste|"
                    + "quelles? tables? (g[èe]re(?:nt)?|d[ée]finit|d[ée]finissent|contient|contiennent|stocke(?:nt)?|"
                    + "permet|permettent|regroupe(?:nt)?|centralise(?:nt)?|couvre(?:nt)?)|"
                    + "dans quelle table)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /**
     * Vrai si la question est réellement une question d'ensemble/d'identification de table, PAS une demande sur
     * un champ précis nommé explicitement. Sans cette exclusion, "explique moi le champ INT.REC" matchait
     * {@code \bexplique} au même titre qu'un vague "explique-moi ce champ" (observé en pratique : la carte "Voir
     * la source" disparaissait totalement pour une question qui nommait pourtant un champ précis) — un identifiant
     * pointé (ex. INT.REC) dans la question signale sans ambiguïté qu'elle porte sur un champ/une table précis,
     * quel que soit le verbe utilisé pour le demander.
     */
    private static boolean isOverviewQuestion(String question) {
        return question != null
                && OVERVIEW_QUESTION_PATTERN.matcher(question).find()
                && !DOTTED_TOKEN_PATTERN.matcher(question).find();
    }

    /** Le LLM (system prompt) admet lui-même que la documentation ne répond pas vraiment à la question : signal
     *  plus fiable qu'un score de similarité pour décider de ne pas afficher de cartes de colonnes hors-sujet. */
    private static final java.util.regex.Pattern UNCERTAIN_ANSWER_PATTERN = java.util.regex.Pattern.compile(
            "n'est pas (sp[ée]cifi[ée]e?|mentionn[ée]e?|indiqu[ée]e?|disponible)|ne (mentionne|permet|pr[ée]cise|indique) pas|"
                    + "je ne peux pas (confirmer|vous donner|r[ée]pondre)|je (vous )?recommande de consulter|"
                    + "cette information n'est pas disponible|il n'y a (pas|aucun)|aucun (champ|[ée]l[ée]ment)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /**
     * Vrai si la réponse EST PRINCIPALEMENT le constat que la documentation ne répond pas à la question (réponse
     * courte, ou constat dès les premiers mots) — jamais seulement parce qu'un motif de {@link
     * #UNCERTAIN_ANSWER_PATTERN} apparaît n'importe où dans un texte par ailleurs long et informatif. Sans cette
     * distinction, une réponse par ailleurs riche et fondée (plusieurs rubriques, plusieurs champs documentés)
     * perdait entièrement ses cartes "Voir la source" et son tableau de champs pour une seule phrase secondaire
     * qui n'admet une lacune que sur un aspect précis (ex. "il n'y a pas d'information documentée sur son
     * fonctionnement", alors que le reste de la réponse documente bien le type, le format, les valeurs
     * autorisées...), ou pire, pour une clause conditionnelle sans rapport ("si une version n'est pas spécifiée,
     * le système lance X automatiquement" décrit un comportement documenté, pas une lacune de la documentation).
     */
    /** En dessous de ce nombre de mots restants après le constat d'incertitude, on considère qu'il ne reste rien
     *  de substantiel après lui (donc que ce constat est bien LA réponse) — voir {@link #isMainlyUncertain}. */
    private static final int UNCERTAIN_ANSWER_TAIL_MIN_WORDS = 12;

    static boolean isMainlyUncertain(String answer) {
        if (answer == null) return false;
        Matcher m = UNCERTAIN_ANSWER_PATTERN.matcher(answer);
        if (!m.find()) return false;
        if (answer.trim().split("\\s+").length <= 40) return true;
        // Réponse courte (ci-dessus) OU rien de substantiel après le constat (ex. "Il n'y a pas de champ X.") :
        // c'est bien LE sujet de la réponse. À l'inverse, un constat suivi d'une part substantielle de contenu
        // (ex. les autres propriétés bien documentées du même champ) n'est qu'un aveu PARTIEL au sein d'une
        // réponse par ailleurs riche : les sources et le tableau de champs restent affichés (voir les deux cas
        // trouvés en pratique : aveu isolé sur UN sous-aspect du champ, et clause conditionnelle décrivant un
        // comportement documenté — ni l'un ni l'autre n'est un aveu global de lacune documentaire).
        String after = answer.substring(m.end()).trim();
        int wordsAfter = after.isEmpty() ? 0 : after.split("\\s+").length;
        return wordsAfter < UNCERTAIN_ANSWER_TAIL_MIN_WORDS;
    }

    /** La question porte-t-elle sur le caractère obligatoire/optionnel du champ ? Détermine si la carte "Voir la
     *  source" affiche la ligne "Saisie" — jamais pour une question qui ne l'a pas demandé (voir buildSourceFields). */
    private static final java.util.regex.Pattern ASKS_MANDATORY_PATTERN = java.util.regex.Pattern.compile(
            "(?iu)\\bobligatoire|\\boptionnel|\\bfacultatif|\\bmandatory|\\brequired|"
                    + "doit([- ]il| elle)? [êe]tre (rempli|renseign[ée])|saisie obligatoire");

    /** "champs" au PLURIEL : distingue "quels sont les champs obligatoires de la table X ?" (énumération de TOUS
     *  les champs de la table) d'une question sur UN champ déjà nommé ("le champ X est-il obligatoire ?", qui
     *  reste au singulier) — voir {@link #enumeratesTableFields}. */
    private static final java.util.regex.Pattern FIELDS_PLURAL_PATTERN = java.util.regex.Pattern.compile("(?iu)\\bchamps\\b");

    /**
     * Vrai si la question demande l'énumération de TOUS les champs obligatoires (ou tous les champs tout court)
     * d'une table déjà identifiée ({@code tableName} non nul). Bug trouvé en pratique : avec {@code topK=5}, une
     * recherche sémantique scopée à la table ne retrouve pas forcément TOUS les champs réellement obligatoires
     * parmi les 5 mieux classés pour cette question précise (ex. ACCOUNT.NUMBER, pourtant marqué "Mandatory
     * field" dans la documentation, absent du contexte retrouvé pour "quels sont les champs obligatoires de
     * AC.STMT.REGEN.REQUEST ?" — une table qui compte 9 champs) : le LLM ne pouvait donc littéralement pas le
     * citer, quelle que soit la qualité du prompt. Ce cas élargit {@code topK} pour couvrir TOUS les champs de la
     * table (voir son utilisation dans {@code ask}/{@code handleStreamingAsk}), pas seulement les 5 plus
     * proches sémantiquement de la question.
     */
    static boolean enumeratesTableFields(String question, String tableName) {
        return tableName != null && !tableName.isBlank()
                && ASKS_MANDATORY_PATTERN.matcher(question).find()
                && FIELDS_PLURAL_PATTERN.matcher(question).find();
    }

    /** Large marge au-dessus du nombre de champs de n'importe quelle table T24 réelle : garantit qu'aucun champ
     *  n'est tronqué par {@code topK} quand {@link #enumeratesTableFields} déclenche l'énumération complète. */
    private static final int ALL_TABLE_FIELDS_TOP_K = 200;

    /** La question porte-t-elle sur le rôle/l'intérêt du champ ? Détermine la ligne "Description" de la carte. */
    private static final java.util.regex.Pattern ASKS_DESCRIPTION_PATTERN = java.util.regex.Pattern.compile(
            "(?iu)sert [àa] quoi|[àa] quoi sert|r[ôo]le|int[ée]r[êe]t|utilit[ée]|"
                    + "que (fait|repr[ée]sente|signifie)|\\bdescription|\\bd[ée]cri[sv]|\\bexplique");

    /** La question porte-t-elle sur une règle de saisie/validation du champ ? Détermine la ligne "Contrôle de
     *  saisie" de la carte, extraite du texte de la Description (voir extractValidationRule). */
    private static final java.util.regex.Pattern ASKS_VALIDATION_PATTERN = java.util.regex.Pattern.compile(
            "(?iu)contr[ôo]le de saisie|\\bvalidation|r[èe]gles? de (saisie|validation)|format attendu|\\bcontrainte");

    /** Extrait la clause "Validation Rules : ..." quand le HelpText T24 en contient une dans la Description brute
     *  (ex. "... This field along with CATEGORY are associated multi-values. Validation Rules: Mandatory if
     *  MD.CSN.RATE.CHANGE is to be invoked."), pour l'afficher isolément dans la carte plutôt que noyée dans le
     *  reste du texte. */
    private static final java.util.regex.Pattern VALIDATION_RULE_PATTERN = java.util.regex.Pattern.compile(
            "(?iu)validation rules?\\s*:\\s*(.+)$");

    /** Messages de politesse/small talk qui ne doivent pas être traités comme des questions T24 (sinon "Aucun résultat pertinent"). */
    private static final java.util.regex.Pattern GREETING_PATTERN = java.util.regex.Pattern.compile(
            "^(bonjour|bonsoir|salut|coucou|hello|hi|hey|yo)[\\s!.,]*$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern THANKS_PATTERN = java.util.regex.Pattern.compile(
            "^(merci( beaucoup)?|thanks|thank you)[\\s!.,]*$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern GOODBYE_PATTERN = java.util.regex.Pattern.compile(
            "^(au revoir|bye|à bientôt|a plus|à plus)[\\s!.,]*$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern HOWAREYOU_PATTERN = java.util.regex.Pattern.compile(
            "^((comment )?[cç]a va|comment allez[- ]vous)\\s*\\??[\\s!.,]*$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private final T24ImportService importService;
    private final ModuleT24Repository moduleRepository;
    private final TableT24Repository tableRepository;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final RetrieverService retrieverService;
    private final AnswerService answerService;
    private final ChatRepository chatRepository;
    private final ChatHistoryRepository chatHistoryRepository;

    public ChatController(T24ImportService importService,
                           ModuleT24Repository moduleRepository,
                           TableT24Repository tableRepository,
                           DocumentService documentService,
                           DocumentRepository documentRepository,
                           RetrieverService retrieverService,
                           AnswerService answerService,
                           ChatRepository chatRepository,
                           ChatHistoryRepository chatHistoryRepository) {
        this.importService = importService;
        this.moduleRepository = moduleRepository;
        this.tableRepository = tableRepository;
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.retrieverService = retrieverService;
        this.answerService = answerService;
        this.chatRepository = chatRepository;
        this.chatHistoryRepository = chatHistoryRepository;
    }

    @PostMapping
    public ResponseEntity<ChatResponseDto> ask(@RequestBody ChatRequestDto request, Authentication auth) {
        String question = request.question();
        String answer;
        List<ColumnResultDto> columns;
        List<TableSummaryDto> tablesForResponse = List.of();
        List<SourceFieldDto> sourceFields = List.of();
        double confidence;

        // Priorité au module explicitement nommé dans la question elle-même (voir explicitModuleCodeIn) sur celui
        // transmis par le frontend : couvre le cas où le frontend n'a pas (encore) mis à jour sa sélection.
        String effectiveModule = explicitModuleCodeIn(question).orElse(request.module());
        boolean moduleSwitched = effectiveModule != null
                && !effectiveModule.equalsIgnoreCase(request.module());

        boolean moduleSet = effectiveModule != null && !effectiveModule.isBlank();
        if (moduleSet && isSimpleListAllTablesRequest(question)) {
            List<TableT24> moduleTables = tableRepository.findByModule_NomModuleIgnoreCase(effectiveModule);
            tablesForResponse = moduleTables.stream().map(ChatController::toSummary).toList();
            answer = moduleTables.isEmpty()
                    ? "Aucune table trouvée pour le module « " + effectiveModule + " »."
                    : moduleTables.size() + " table(s) dans le module « " + effectiveModule + " » :";
            columns = List.of();
            confidence = 1.0;
            Chat chat = resolveChat(request.conversationId(), question, auth.getName());
            saveMessage(chat, "question", question);
            saveMessage(chat, "reponse", answer);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepository.save(chat);
            return ResponseEntity.ok(new ChatResponseDto(answer, columns, "OK", confidence, LocalDateTime.now(),
                    chat.getId(), tablesForResponse, List.of(), moduleSwitched ? effectiveModule : null));
        }

        String smallTalkReply = smallTalkReply(question);
        if (smallTalkReply != null) {
            answer = smallTalkReply;
            columns = List.of();
            confidence = 1.0;
        } else {
            // Historique récent du même fil (avant la question courante, pas encore enregistrée) : permet au LLM
            // de comprendre les questions de suivi ("réponds brièvement", "et pour X ?") au lieu de traiter
            // chaque message comme une requête totalement indépendante.
            List<ChatHistory> priorHistory = request.conversationId() != null
                    ? chatHistoryRepository.findByChat_IdOrderByCreatedAtAsc(request.conversationId())
                    : List.of();
            // Rappelle systématiquement le libellé officiel du module concerné (ex. « LD » = « Lending ») : sans
            // ça, le LLM devine le sens du module à partir des seuls noms de tables/colonnes retrouvés, et peut
            // halluciner un rôle complètement faux (ex. « SC » pris pour "gestion des comptes clients" à partir
            // de SC.CTDY.MT548.INSTRUCT, alors que SC = Securities). S'applique aux deux chemins (contexte réel
            // trouvé ou repli connaissances générales), pas seulement au repli.
            String conversationHistory = withModuleReminder(recentHistoryText(priorHistory), effectiveModule);

            // Reformule la question pour la RECHERCHE uniquement (jamais pour la réponse finale, qui reste basée
            // sur `question`) : résout les références implicites à l'historique ("il appartient à quelle table ?"
            // -> "MIN.COMM.AMT.LCCY appartient à quelle table ?"), ce qui permet en plus à la correspondance
            // exacte par nom de champ de se déclencher directement sur cette question de suivi, sans dépendre
            // d'un score sémantique. Volontairement basée sur le dernier échange seul (pas `conversationHistory`,
            // qui inclut le rappel de module et jusqu'à 8 messages) : voir {@link #lastExchangeText}. Inchangée si
            // aucun historique ou si le LLM est indisponible.
            // Question relevant clairement d'un AUTRE module : jamais de reformulation à partir de l'historique du module
            // courant (elle y importerait un identifiant sans rapport avec le nouveau sujet).
            // Type de question (voir QuestionIntent) : les questions générales sur un module et les questions de suivi
            // ont un contexte déterministe (specialContext), sans reformulation ni recherche de champs.
            QuestionIntent intent = QuestionIntent.classify(question, !priorHistory.isEmpty());
            var special = specialContext(intent, question, effectiveModule, priorHistory);
            boolean useSpecial = !special.isEmpty();
            if (useSpecial && intent.isFollowUp()) {
                conversationHistory = withModuleReminder(questionsOnlyHistory(priorHistory), effectiveModule);
            }

            String retrievalQuery = useSpecial || !moduleMismatchNote(question, moduleSet ? effectiveModule : null).isEmpty()
                    ? question
                    : answerService.rewriteQuery(question, lastExchangeText(priorHistory));

            // Sur le texte LITTÉRAL de la question, jamais sur retrievalQuery (reformulation LLM à partir de
            // l'historique) : voir le commentaire équivalent sur resolveInlineTableName — la reformulation peut
            // introduire ou transformer un token d'une façon qui déclenche ce court-circuit à tort (observé en
            // pratique : question bloquée alors que la table nommée existe bel et bien).
            String blockedMessage = useSpecial ? null : blockedIdentifierMessage(question, effectiveModule);
            if (blockedMessage != null) {
                // Vérification déterministe en base (rapide, sans LLM), avant même de lancer la recherche : si la
                // question nomme explicitement un identifiant T24 (table/colonne pointée) qui n'existe réellement
                // pas — ou qui existe mais dans un AUTRE module que celui sélectionné —, inutile de lancer FAISS
                // puis le LLM : la réponse ne pourrait qu'être inventée ou hors sujet. Court-circuite tout le reste
                // de la recherche/génération : plus rapide (pas d'appel LLM) et 100% fiable pour ce cas précis.
                answer = blockedMessage;
                columns = List.of();
                confidence = 0.0;
            } else {

            // Recherche globale (aucun module/table sélectionné) : le nuage sémantique est beaucoup plus large et
            // bruyant, le score décroît sans coupure nette entre "bon résultat" et "vaguement lié" (ex. 0.50 -> 0.43
            // sur 5 candidats). On ne garde donc que le meilleur résultat pour éviter d'afficher des colonnes hors-sujet.
            // Une recherche scopée à un module/table est déjà propre : on garde 5 résultats, tous utiles (ex. lister
            // plusieurs champs obligatoires d'une même table).
            // Détection du nom de table à partir du texte LITTÉRAL de la question (jamais de retrievalQuery, qui
            // peut avoir été reformulé par le LLM à partir de l'historique) : observé en pratique, cette
            // reformulation peut faire disparaître le mot "table" ou changer l'ordre des mots de la question
            // ("la table md.paramter contient quoi exactement ?"), empêchant resolveInlineTableName de reconnaître
            // la table pourtant nommée explicitement — la recherche bascule alors, à tort, sur tout le module
            // (bruyant, avec le résumé général du module en plus), au lieu de rester scopée à cette seule table.
            String effectiveTableName = inheritPreviousTable(
                    resolveInlineTableName(question, effectiveModule, request.tableName()),
                    question, priorHistory, effectiveModule);
            boolean tableScoped = effectiveTableName != null && !effectiveTableName.isBlank();
            boolean scoped = (effectiveModule != null && !effectiveModule.isBlank()) || tableScoped;
            int topK = tableScoped && enumeratesTableFields(question, effectiveTableName) ? ALL_TABLE_FIELDS_TOP_K
                    : scoped ? 5 : 1;
            double confidenceThreshold = tableScoped ? TABLE_SCOPED_CONFIDENCE_THRESHOLD : CONFIDENCE_THRESHOLD;

            var rawResults = useSpecial ? special
                    : retrieverService.search(retrievalQuery, effectiveModule, effectiveTableName, topK);
            confidence = useSpecial ? 1.0 : rawResults.isEmpty() ? 0.0 : rawResults.get(0).score();

            // Filet de sécurité si la reformulation n'a pas suffi (LLM indisponible, ou question de suivi encore
            // trop ambiguë) : on retente en ancrant sur la dernière question de l'utilisateur — pas seulement
            // quand le score est sous le seuil, car une question vague peut par coïncidence dépasser ce seuil sur
            // un résultat sémantique sans rapport (score FAISS trompeur). On ne bascule JAMAIS sur cette version
            // ancrée pour un simple score "un peu meilleur" : une question déjà autonome et sans rapport avec le
            // sujet précédent (ex. une nouvelle question conceptuelle) obtient presque toujours un score plus
            // élevé une fois "enrichie" par le texte de l'ancienne question, sans que ce soit pour autant la
            // bonne réponse (observé en pratique, plusieurs fois : le sujet précédent "gagnait" à tort). Seule une
            // correspondance CERTAINE (exacte) sur la version ancrée justifie de l'utiliser.
            if (!useSpecial && confidence < EXACT_MATCH_SCORE && !priorHistory.isEmpty()) {
                String lastQuestion = lastUserQuestion(priorHistory);
                if (lastQuestion != null) {
                    var retryResults = retrieverService.search(lastQuestion + " " + retrievalQuery,
                            effectiveModule, effectiveTableName, topK);
                    double retryConfidence = retryResults.isEmpty() ? 0.0 : retryResults.get(0).score();
                    if (retryConfidence >= EXACT_MATCH_SCORE) {
                        rawResults = retryResults;
                        confidence = retryConfidence;
                    }
                }
            }

            if (rawResults.isEmpty() || confidence < confidenceThreshold) {
                // Rien de pertinent dans la documentation T24 indexée : on ne s'arrête pas à un message générique,
                // on interroge le LLM sur ses connaissances générales T24/bancaires, en indiquant clairement que
                // ce n'est pas une réponse issue de la documentation officielle de la banque. (Le cas d'un
                // identifiant précis inexistant est déjà écarté plus haut par namesNonexistentIdentifier, avant
                // même cette recherche.)
                // Sauf si la question relève clairement d'un AUTRE module (voir moduleMismatchNote) : la suggestion
                // de changer de module suffit alors, une réponse "connaissances générales" bâtie sur l'historique
                // du module actuel serait hors sujet.
                boolean switchOnly = !moduleMismatchNote(question, moduleSet ? effectiveModule : null).isEmpty();
                boolean outOfDomain = !switchOnly && question.length() > 25 && !answerService.isInDomain(question);
                String generalAnswer = switchOnly ? ""
                        : outOfDomain ? OUT_OF_DOMAIN_MESSAGE
                        : answerService.generateWithoutContext(question, conversationHistory);
                answer = generalAnswer != null
                        ? generalAnswer
                        : "Aucun résultat pertinent trouvé pour cette question, ni dans la documentation T24 ni ailleurs. Essayez de reformuler, de décrire ce que vous cherchez différemment, ou de préciser le module/la table.";
                columns = List.of();
            } else {
                // Le score du meilleur résultat ne garantit pas que les suivants le soient (recherche scopée à
                // topK=5) : sans ce filtre par résultat, des champs sans rapport s'affichent et polluent surtout
                // le contexte donné au LLM (réponse qui mélange la description de plusieurs champs différents).
                // Énumération de TOUS les champs (obligatoires ou non) de la table (voir enumeratesTableFields) :
                // jamais de filtre par score, qui pourrait justement en exclure un pourtant réellement obligatoire
                // mais peu proche sémantiquement du texte exact de la question (c'est précisément le bug corrigé).
                var results = useSpecial || enumeratesTableFields(question, effectiveTableName) ? rawResults
                        : filterByConfidence(rawResults, confidenceThreshold, tableScoped);
                String rawAnswer = results.stream()
                        .filter(r -> !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(r.document().getColumnName()))
                        .map(ChatController::rawFallbackEntry)
                        .collect(Collectors.joining("\n\n"));

                // Un champ "trouvé" (même par correspondance exacte) peut n'avoir aucune vraie description dans le
                // HelpText T24 (juste le texte générique "Help Text unavailable...", filtré par extractDescription) :
                // dans ce cas, laisser le LLM "expliquer" ce champ à partir de ce non-contexte reviendrait à
                // halluciner une réponse qui se ferait passer pour officielle. On bascule alors sur le repli
                // connaissances générales, comme si rien n'avait été trouvé.
                boolean anyRealDescription = useSpecial || results.stream()
                        .anyMatch(r -> !extractDescription(r.document().getContent()).isBlank());

                // (Le cas d'un identifiant précis inexistant est déjà écarté par namesNonexistentIdentifier avant
                // même la recherche ; ici, anyRealDescription==false ne peut venir que d'un champ réellement
                // trouvé mais sans description utile dans le HelpText — pas d'un nom inventé.)
                String llmAnswer = anyRealDescription
                        ? answerService.generate(question, results, conversationHistory, effectiveModule, intent)
                        : answerService.generateWithoutContext(question, conversationHistory);
                answer = llmAnswer != null ? repairIdentifiers(llmAnswer, results)
                        : useSpecial ? specialFallback(results) : rawAnswer;
                // Deux cas où les chunks retrouvés n'ont servi qu'à nourrir la synthèse du LLM, pas à désigner des
                // champs précis à afficher en cartes : (1) question générale sur le module/la table dans son
                // ensemble ou visant à identifier une table ("décris-moi ce module", "quelle table gère X"), ou
                // (2) le LLM admet lui-même, dans sa réponse, que la doc ne couvre pas vraiment le sujet (ex.
                // "quels utilisateurs..." : score élevé mais hors-sujet) — ce deuxième signal est plus fiable
                // qu'un motif sur la question, puisqu'il vient de la réponse réelle.
                boolean overviewQuestion = isOverviewQuestion(question) || intent == QuestionIntent.MODULE_GENERAL;
                boolean uncertainAnswer = isMainlyUncertain(answer);
                columns = (overviewQuestion || uncertainAnswer)
                        ? List.of()
                        : columnsMentionedInAnswer(results, answer);
                // Pour une question d'identification de table, la carte "Voir la source" montre la table
                // identifiée (jamais une liste de colonnes qui laisserait croire, à tort, que ces champs précis
                // sont "la réponse" — voir buildTableOnlySourceFields) ; pour une réponse incertaine, rien à montrer.
                sourceFields = uncertainAnswer
                        ? List.of()
                        : overviewQuestion
                                ? buildTableOnlySourceFields(results)
                                : buildSourceFields(results, answer, question);
                answer = answer + fieldsTableFor(results, useSpecial, answer);
            }
            }
        }

        answer = (answer + moduleMismatchNote(question, moduleSet ? effectiveModule : null)).strip();

        Chat chat = resolveChat(request.conversationId(), question, auth.getName());
        saveMessage(chat, "question", question);
        saveMessage(chat, "reponse", answer, sourceFields);
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);

        return ResponseEntity.ok(new ChatResponseDto(answer, columns, "OK", confidence, LocalDateTime.now(),
                chat.getId(), List.of(), sourceFields, moduleSwitched ? effectiveModule : null));
    }

    /**
     * Note ajoutée en fin de réponse quand le vocabulaire de {@code question} correspond sans ambiguïté à un
     * AUTRE module que celui déjà sélectionné (voir AnswerService#suggestOtherModule) : la recherche restant
     * confinée au module choisi, elle ne peut jamais s'en rendre compte elle-même et finirait sinon par répondre
     * dans le mauvais module sans jamais le signaler (observé en pratique : question sur le crédit bancaire,
     * module AC déjà sélectionné, réponse construite à partir de champs de routage bancaire sans rapport plutôt
     * que de suggérer le module LD - Lending). Chaîne vide si rien ne correspond ou si aucun module n'est
     * sélectionné.
     */
    private static final Pattern EXPLICIT_MODULE_PATTERN =
            Pattern.compile("(?iu)\\bmodule\\s*(?:«\\s*)?([A-Za-z]{2,10})\\b");

    /**
     * Code de module explicitement nommé dans {@code question} (ex. "présente-moi le module EB"), s'il correspond
     * à un module actif connu ({@link ActiveModules}) : permet de corriger une sélection de module périmée côté
     * frontend — l'utilisateur change de sujet dans une question libre sans repasser par le sélecteur de module —
     * plutôt que de répondre silencieusement sur l'ancien module (observé en pratique : module déjà sélectionné à
     * MD, question "présente-moi le module EB" → réponse construite sur MD, sans aucun indice pour l'utilisateur ;
     * {@link #moduleMismatchNote} ne couvre pas ce cas, puisqu'il ne détecte qu'une correspondance par vocabulaire
     * métier, jamais un code de module cité tel quel). Vide si aucun code de module actif ne suit le mot "module".
     */
    static Optional<String> explicitModuleCodeIn(String question) {
        if (question == null) return Optional.empty();
        Matcher m = EXPLICIT_MODULE_PATTERN.matcher(question);
        while (m.find()) {
            String code = m.group(1).toUpperCase();
            if (ActiveModules.CODE_SET.contains(code)) return Optional.of(code);
        }
        return Optional.empty();
    }

    private String moduleMismatchNote(String question, String currentModule) {
        if (currentModule == null) return "";
        String suggested = answerService.suggestOtherModule(question, currentModule);
        if (suggested == null) return "";
        String label = moduleRepository.findByNomModuleIgnoreCase(suggested).map(ModuleT24::getLabel).orElse(null);
        return "\n\nRemarque : cette question semble plutôt concerner le module " + suggested
                + (label != null && !label.isBlank() ? " (" + label + ")" : "")
                + " plutôt que le module actuellement sélectionné (" + currentModule + ").";
    }

    /**
     * Variante "streaming" de {@link #ask} : le texte de la réponse est envoyé au fil de l'eau (événements SSE
     * "chunk"), pour un affichage progressif côté frontend au lieu d'attendre la réponse complète — important sur
     * ce matériel (LLM local sans GPU, plusieurs secondes voire dizaines de secondes par réponse). Un événement
     * "done" final porte les métadonnées (colonnes, tables, conversationId) une fois le texte entièrement reçu.
     * Reproduit la même logique de routage que {@link #ask} (small talk / liste des tables / repli connaissances
     * générales / RAG) : gardée volontairement séparée pour ne pas complexifier la version non-streaming existante.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody ChatRequestDto request, Authentication auth) {
        SseEmitter emitter = new SseEmitter(0L);
        String username = auth.getName();
        Thread thread = new Thread(() -> {
            try {
                handleStreamingAsk(request, username, emitter);
            } catch (Exception e) {
                log.error("Erreur pendant le streaming de la réponse", e);
                try { emitter.completeWithError(e); } catch (Exception ignored) { }
            }
        }, "chat-stream");
        thread.setDaemon(true);
        thread.start();
        return emitter;
    }

    private void handleStreamingAsk(ChatRequestDto request, String username, SseEmitter emitter) throws IOException {
        String question = request.question();
        // Voir le commentaire équivalent dans ask() : priorité au module explicitement nommé dans la question.
        String effectiveModule = explicitModuleCodeIn(question).orElse(request.module());
        boolean moduleSwitched = effectiveModule != null
                && !effectiveModule.equalsIgnoreCase(request.module());
        boolean moduleSet = effectiveModule != null && !effectiveModule.isBlank();

        if (moduleSet && isSimpleListAllTablesRequest(question)) {
            List<TableT24> moduleTables = tableRepository.findByModule_NomModuleIgnoreCase(effectiveModule);
            List<TableSummaryDto> tablesForResponse = moduleTables.stream().map(ChatController::toSummary).toList();
            String answer = moduleTables.isEmpty()
                    ? "Aucune table trouvée pour le module « " + effectiveModule + " »."
                    : moduleTables.size() + " table(s) dans le module « " + effectiveModule + " » :";
            sendChunk(emitter, answer);
            Chat chat = resolveChat(request.conversationId(), question, username);
            saveMessage(chat, "question", question);
            saveMessage(chat, "reponse", answer);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepository.save(chat);
            sendDone(emitter, List.of(), tablesForResponse, chat.getId(), 1.0, List.of(),
                    moduleSwitched ? effectiveModule : null);
            emitter.complete();
            return;
        }

        String smallTalkReply = smallTalkReply(question);
        if (smallTalkReply != null) {
            sendChunk(emitter, smallTalkReply);
            Chat chat = resolveChat(request.conversationId(), question, username);
            saveMessage(chat, "question", question);
            saveMessage(chat, "reponse", smallTalkReply);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepository.save(chat);
            sendDone(emitter, List.of(), List.of(), chat.getId(), 1.0, List.of(), null);
            emitter.complete();
            return;
        }

        List<ChatHistory> priorHistory = request.conversationId() != null
                ? chatHistoryRepository.findByChat_IdOrderByCreatedAtAsc(request.conversationId())
                : List.of();
        String conversationHistory = withModuleReminder(recentHistoryText(priorHistory), effectiveModule);

        // Voir le commentaire équivalent dans ask() : reformulation pour la RECHERCHE uniquement, basée sur le
        // dernier échange seul.
        // Question relevant clairement d'un AUTRE module : jamais de reformulation à partir de l'historique du module
        // courant (elle y importerait un identifiant sans rapport avec le nouveau sujet).
        // Type de question et contexte déterministe : voir ask() et specialContext.
        QuestionIntent intent = QuestionIntent.classify(question, !priorHistory.isEmpty());
        var special = specialContext(intent, question, effectiveModule, priorHistory);
        boolean useSpecial = !special.isEmpty();
        if (useSpecial && intent.isFollowUp()) {
            conversationHistory = withModuleReminder(questionsOnlyHistory(priorHistory), effectiveModule);
        }
        String retrievalQuery = useSpecial || !moduleMismatchNote(question, moduleSet ? effectiveModule : null).isEmpty()
                ? question
                : answerService.rewriteQuery(question, lastExchangeText(priorHistory));

        // Voir le commentaire équivalent dans ask() : sur le texte littéral de la question, jamais sur
        // retrievalQuery (reformulation LLM qui peut déclencher ce court-circuit à tort).
        String blockedMessage = useSpecial ? null : blockedIdentifierMessage(question, effectiveModule);
        if (blockedMessage != null) {
            // Voir le commentaire équivalent dans ask() : vérification déterministe en base avant même la
            // recherche, pour ne jamais lancer FAISS/LLM sur un identifiant inexistant ou d'un autre module.
            String notFound = blockedMessage;
            sendChunk(emitter, notFound);
            Chat chat = resolveChat(request.conversationId(), question, username);
            saveMessage(chat, "question", question);
            saveMessage(chat, "reponse", notFound);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepository.save(chat);
            sendDone(emitter, List.of(), List.of(), chat.getId(), 0.0, List.of(),
                    moduleSwitched ? effectiveModule : null);
            emitter.complete();
            return;
        }

        // Voir le commentaire équivalent dans ask() : détection du nom de table sur le texte littéral de la
        // question, jamais sur retrievalQuery (reformulation LLM qui peut faire disparaître le mot "table").
        String effectiveTableName = inheritPreviousTable(
                    resolveInlineTableName(question, effectiveModule, request.tableName()),
                    question, priorHistory, effectiveModule);
        boolean tableScoped = effectiveTableName != null && !effectiveTableName.isBlank();
        boolean scoped = moduleSet || tableScoped;
        int topK = tableScoped && enumeratesTableFields(question, effectiveTableName) ? ALL_TABLE_FIELDS_TOP_K
                : scoped ? 5 : 1;
        double confidenceThreshold = tableScoped ? TABLE_SCOPED_CONFIDENCE_THRESHOLD : CONFIDENCE_THRESHOLD;

        var rawResults = useSpecial ? special
                : retrieverService.search(retrievalQuery, effectiveModule, effectiveTableName, topK);
        double confidence = useSpecial ? 1.0 : rawResults.isEmpty() ? 0.0 : rawResults.get(0).score();

        // Voir le commentaire équivalent dans ask() : on retente toujours la version ancrée sur l'historique
        // (pas seulement sous le seuil), une question vague pouvant dépasser ce seuil par coïncidence sur un
        // résultat sémantique sans rapport.
        if (!useSpecial && confidence < EXACT_MATCH_SCORE && !priorHistory.isEmpty()) {
            String lastQuestion = lastUserQuestion(priorHistory);
            if (lastQuestion != null) {
                var retryResults = retrieverService.search(lastQuestion + " " + retrievalQuery,
                        effectiveModule, effectiveTableName, topK);
                double retryConfidence = retryResults.isEmpty() ? 0.0 : retryResults.get(0).score();
                if (retryConfidence >= EXACT_MATCH_SCORE) {
                    rawResults = retryResults;
                    confidence = retryConfidence;
                }
            }
        }

        StringBuilder fullAnswer = new StringBuilder();
        List<ColumnResultDto> columns;
        List<SourceFieldDto> sourceFields = List.of();

        if (rawResults.isEmpty() || confidence < confidenceThreshold) {
            columns = List.of();
            // (Le cas d'un identifiant précis inexistant est déjà écarté plus haut par namesNonexistentIdentifier,
            // avant même cette recherche.)
            // Question relevant clairement d'un AUTRE module : la suggestion de changement de module (ajoutée plus
            // bas) suffit, pas de réponse "connaissances générales" hors sujet (voir moduleMismatchNote).
            boolean switchOnly = !moduleMismatchNote(question, moduleSet ? effectiveModule : null).isEmpty();
            boolean outOfDomain = !switchOnly && question.length() > 25 && !answerService.isInDomain(question);
            if (outOfDomain) {
                fullAnswer.append(OUT_OF_DOMAIN_MESSAGE);
                sendChunk(emitter, OUT_OF_DOMAIN_MESSAGE);
            } else if (!switchOnly) {
                answerService.generateWithoutContextStreaming(question, conversationHistory,
                        piece -> { fullAnswer.append(piece); sendChunkUnchecked(emitter, piece); });
                if (fullAnswer.length() == 0) {
                    String fallback = "Aucun résultat pertinent trouvé pour cette question, ni dans la documentation T24 ni ailleurs. Essayez de reformuler, de décrire ce que vous cherchez différemment, ou de préciser le module/la table.";
                    fullAnswer.append(fallback);
                    sendChunk(emitter, fallback);
                }
            }
        } else {
            var results = useSpecial || enumeratesTableFields(question, effectiveTableName) ? rawResults
                    : filterByConfidence(rawResults, confidenceThreshold, tableScoped);
            boolean anyRealDescription = useSpecial || results.stream()
                    .anyMatch(r -> !extractDescription(r.document().getContent()).isBlank());

            if (anyRealDescription) {
                // Volontairement non-streamé (appel bloquant à generate(), pas generateStreaming) : c'est cette
                // méthode qui vérifie après coup qu'aucun identifiant hors contexte n'est cité comme sujet de la
                // réponse (voir AnswerServiceImpl#citesOnlyContextIdentifiers), avec une nouvelle tentative
                // automatique sinon — une vérification impossible à faire correctement token par token sans
                // pouvoir "annuler" un texte déjà affiché à l'utilisateur.
                String grounded = answerService.generate(question, results, conversationHistory, effectiveModule, intent);
                if (grounded != null) {
                    grounded = repairIdentifiers(grounded, results);
                    fullAnswer.append(grounded);
                    sendChunk(emitter, grounded);
                }
            } else {
                answerService.generateWithoutContextStreaming(question, conversationHistory,
                        piece -> { fullAnswer.append(piece); sendChunkUnchecked(emitter, piece); });
            }
            if (fullAnswer.length() == 0) {
                String rawAnswer = useSpecial ? specialFallback(results) : results.stream()
                        .filter(r -> !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(r.document().getColumnName()))
                        .map(ChatController::rawFallbackEntry)
                        .collect(Collectors.joining("\n\n"));
                fullAnswer.append(rawAnswer);
                sendChunk(emitter, rawAnswer);
            }
            boolean overviewQuestion = isOverviewQuestion(question) || intent == QuestionIntent.MODULE_GENERAL;
            boolean uncertainAnswer = isMainlyUncertain(fullAnswer.toString());
            columns = (overviewQuestion || uncertainAnswer)
                    ? List.of()
                    : columnsMentionedInAnswer(results, fullAnswer.toString());
            sourceFields = uncertainAnswer
                    ? List.of()
                    : overviewQuestion
                            ? buildTableOnlySourceFields(results)
                            : buildSourceFields(results, fullAnswer.toString(), question);
            String fieldsTable = fieldsTableFor(results, useSpecial, fullAnswer.toString());
            if (!fieldsTable.isEmpty()) {
                fullAnswer.append(fieldsTable);
                sendChunk(emitter, fieldsTable);
            }
        }

        String mismatchNote = moduleMismatchNote(question, moduleSet ? effectiveModule : null);
        if (!mismatchNote.isEmpty()) {
            String toSend = fullAnswer.length() == 0 ? mismatchNote.stripLeading() : mismatchNote;
            fullAnswer.append(toSend);
            sendChunk(emitter, toSend);
        }
        String answer = fullAnswer.toString();
        Chat chat = resolveChat(request.conversationId(), question, username);
        saveMessage(chat, "question", question);
        saveMessage(chat, "reponse", answer, sourceFields);
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);

        sendDone(emitter, columns, List.of(), chat.getId(), confidence, sourceFields,
                moduleSwitched ? effectiveModule : null);
        emitter.complete();
    }

    private void sendChunk(SseEmitter emitter, String text) throws IOException {
        emitter.send(SseEmitter.event().name("chunk").data(Map.of("text", text)));
    }

    /** Comme {@link #sendChunk}, mais pour un usage dans une lambda ({@code Consumer<String>} ne peut pas déclarer {@code throws}). */
    private void sendChunkUnchecked(SseEmitter emitter, String text) {
        try {
            sendChunk(emitter, text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendDone(SseEmitter emitter, List<ColumnResultDto> columns, List<TableSummaryDto> tables,
                           Long conversationId, double confidence, List<SourceFieldDto> sourceFields,
                           String resolvedModule) throws IOException {
        // Map.of refuse les valeurs null : resolvedModule reste absent (vide côté frontend) tant qu'aucun
        // changement de module n'a été détecté, plutôt que d'envoyer une entrée à valeur null.
        Map<String, Object> data = new LinkedHashMap<>(Map.of(
                "columns", columns, "tables", tables, "conversationId", conversationId,
                "confidence", confidence, "status", "OK", "sourceFields", sourceFields));
        if (resolvedModule != null) data.put("resolvedModule", resolvedModule);
        emitter.send(SseEmitter.event().name("done").data(data));
    }

    /** Renvoie une réponse de politesse toute faite si la question est du small talk (salutation/remerciement/au revoir), sinon null. */
    private static String smallTalkReply(String question) {
        String q = question == null ? "" : question.trim();
        if (GREETING_PATTERN.matcher(q).matches() || HOWAREYOU_PATTERN.matcher(q).matches()) {
            return "Bonjour ! Je suis l'assistant T24 BIAT IT. Vous pouvez me poser une question précise (ex. « à quoi sert CUSTOMER.ID dans LD.LOANS.MASTER ? ») ou décrire ce que vous cherchez (ex. « quelle colonne permet de calculer le taux d'intérêt d'un prêt ? »).";
        }
        if (THANKS_PATTERN.matcher(q).matches()) {
            return "Avec plaisir ! N'hésitez pas si vous avez d'autres questions sur T24.";
        }
        if (GOODBYE_PATTERN.matcher(q).matches()) {
            return "Au revoir ! N'hésitez pas à revenir si vous avez d'autres questions sur T24.";
        }
        return null;
    }

    private Chat resolveChat(Long chatId, String question, String username) {
        if (chatId != null) {
            return chatRepository.findByIdAndUsername(chatId, username)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation introuvable : " + chatId));
        }
        Chat chat = new Chat();
        chat.setUsername(username);
        chat.setTitle(question.length() > 50 ? question.substring(0, 50) + "…" : question);
        return chatRepository.save(chat);
    }

    private void saveMessage(Chat chat, String messageType, String content) {
        saveMessage(chat, messageType, content, null);
    }

    /**
     * Persiste aussi les tables/colonnes sources de cette réponse (voir {@link SourceFieldDto}, déjà construites
     * par {@link #buildSourceFields} selon ce que la question demandait), pour que le bouton "Voir la source" et
     * la carte qu'il ouvre côté frontend restent disponibles après un rafraîchissement de page ou un changement
     * de conversation — cette info n'existait sinon que le temps de la requête HTTP, jamais stockée.
     */
    private void saveMessage(Chat chat, String messageType, String content, List<SourceFieldDto> sourceFields) {
        ChatHistory message = new ChatHistory();
        message.setChat(chat);
        message.setMessageType(messageType);
        message.setContent(content);
        if (sourceFields != null && !sourceFields.isEmpty()) {
            try {
                message.setSourceColumns(SOURCE_FIELDS_JSON.writeValueAsString(sourceFields));
            } catch (Exception e) {
                log.warn("Impossible de sérialiser les colonnes sources pour l'historique", e);
            }
        }
        chatHistoryRepository.save(message);
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ChatSummaryDto>> conversations(Authentication auth) {
        List<Chat> list = isSuperAdmin(auth)
                ? chatRepository.findAllByOrderByPinnedDescUpdatedAtDescIdDesc()
                : chatRepository.findByUsernameOrderByPinnedDescUpdatedAtDescIdDesc(auth.getName());
        return ResponseEntity.ok(list.stream().map(ChatController::toSummary).toList());
    }

    @GetMapping("/conversations/search")
    public ResponseEntity<List<ChatSummaryDto>> searchConversations(@RequestParam String q, Authentication auth) {
        List<Chat> list = isSuperAdmin(auth)
                ? chatRepository.findByTitleContainingIgnoreCaseOrderByPinnedDescUpdatedAtDescIdDesc(q)
                : chatRepository.findByUsernameAndTitleContainingIgnoreCaseOrderByPinnedDescUpdatedAtDescIdDesc(auth.getName(), q);
        return ResponseEntity.ok(list.stream().map(ChatController::toSummary).toList());
    }

    /** Le superAdmin voit l'historique de tous les utilisateurs ; les autres rôles ne voient que le leur. */
    private static boolean isSuperAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPERADMIN"));
    }

    @PostMapping("/conversations/{id}/pin")
    public ResponseEntity<ChatSummaryDto> pinConversation(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(setPinned(id, auth.getName(), true));
    }

    @DeleteMapping("/conversations/{id}/pin")
    public ResponseEntity<ChatSummaryDto> unpinConversation(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(setPinned(id, auth.getName(), false));
    }

    private ChatSummaryDto setPinned(Long id, String username, boolean pinned) {
        Chat chat = chatRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("Conversation introuvable : " + id));
        chat.setPinned(pinned);
        return toSummary(chatRepository.save(chat));
    }

    private static ChatSummaryDto toSummary(Chat c) {
        return new ChatSummaryDto(c.getId(), c.getTitle(), c.getUpdatedAt(), c.isPinned(), c.getUsername());
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<ChatHistoryDto>> conversationMessages(@PathVariable Long id, Authentication auth) {
        boolean exists = isSuperAdmin(auth)
                ? chatRepository.findById(id).isPresent()
                : chatRepository.findByIdAndUsername(id, auth.getName()).isPresent();
        if (!exists) throw new IllegalArgumentException("Conversation introuvable : " + id);
        return ResponseEntity.ok(chatHistoryRepository.findByChat_IdOrderByCreatedAtAsc(id).stream()
                .map(m -> new ChatHistoryDto(m.getMessageType(), m.getContent(), m.getCreatedAt(),
                        deserializeSourceFields(m.getSourceColumns())))
                .toList());
    }

    /** Relit les colonnes/tables sources persistées avec un message (voir {@link ChatHistory#getSourceColumns()}),
     *  pour que le bouton "Voir la source" reste disponible après un rafraîchissement de page. */
    private List<SourceFieldDto> deserializeSourceFields(String sourceColumnsJson) {
        if (sourceColumnsJson == null || sourceColumnsJson.isBlank()) return List.of();
        try {
            return SOURCE_FIELDS_JSON.readValue(sourceColumnsJson, new TypeReference<List<SourceFieldDto>>() {});
        } catch (Exception e) {
            log.warn("Impossible de désérialiser les colonnes sources de l'historique", e);
            return List.of();
        }
    }

    @DeleteMapping("/conversations/{id}")
    @Transactional
    public ResponseEntity<?> deleteConversation(@PathVariable Long id, Authentication auth) {
        Chat chat = chatRepository.findByIdAndUsername(id, auth.getName())
                .orElseThrow(() -> new IllegalArgumentException("Conversation introuvable : " + id));
        chatHistoryRepository.deleteByChat_Id(id);
        chatRepository.delete(chat);
        return ResponseEntity.noContent().build();
    }

    /**
     * Préfixe l'historique de conversation avec un rappel du libellé officiel du module concerné (ex. « LD » =
     * « Lending »). Appliqué systématiquement, aux deux chemins de réponse (contexte T24 réel trouvé, ou repli
     * connaissances générales) : sans ce rappel, le LLM devine le sens du module à partir des seuls noms de
     * tables/colonnes retrouvés et peut halluciner un rôle complètement faux (observé : « SC » pris pour "gestion
     * des comptes clients" à partir de SC.CTDY.MT548.INSTRUCT, alors que SC = Securities). Formulation directive
     * (« ce sont deux noms pour la même chose ») : sans ça, le LLM traite parfois le code et le libellé comme deux
     * entités distinctes ("je connais Lending mais pas LD").
     */
    private String withModuleReminder(String conversationHistory, String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) return conversationHistory;
        String label = CORE_MODULE_LABELS.get(moduleCode.toUpperCase());
        if (label == null) {
            label = moduleRepository.findByNomModuleIgnoreCase(moduleCode).map(ModuleT24::getLabel).orElse(null);
        }
        if (label == null || label.isBlank()) return conversationHistory;
        String reminder = "Rappel important : le module dont il est question ici a pour code « " + moduleCode
                + " » et pour nom officiel « " + label + " » — ce sont deux noms pour EXACTEMENT le même module, "
                + "jamais deux choses différentes. Ne déduis JAMAIS le sens ou le rôle de ce module à partir des "
                + "seuls noms de tables/colonnes retrouvés dans le contexte : base-toi sur ce nom officiel. Dans "
                + "une phrase naturelle (pas dans un nom de table/colonne), désigne toujours ce module par son nom "
                + "« " + label + " » (traduit naturellement en français si besoin), JAMAIS par le seul code « "
                + moduleCode + " », sauf si l'utilisateur a lui-même utilisé ce code dans sa question.";
        return (conversationHistory == null || conversationHistory.isBlank())
                ? reminder
                : reminder + "\n\n" + conversationHistory;
    }

    /**
     * Si aucune table n'est explicitement sélectionnée (dialogue guidé/bulle) mais que la question nomme, en
     * langage naturel, une vraie table du module courant (ex. « restant dans la table TXN.TYPE.CONDITION » pour
     * la table réelle « MD.TXN.TYPE.CONDITION »), la reconnaît et la retourne pour scoper la recherche dessus :
     * sans ça, la recherche resterait scopée au module entier (parfois des centaines de champs), diluant
     * complètement le score sémantique du champ recherché au point de ne plus rien trouver. Accepte le nom
     * complet ou le nom sans le préfixe « MODULE. » (souvent omis puisque le module est déjà implicite).
     */
    /** Un token candidat pour la reconnaissance floue de nom de table/colonne : au moins un mot, avec ou sans points. */
    private static final Pattern IDENTIFIER_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)*");

    /** Un nom pointé façon identifiant T24 (ex: LC.DOCUMENT.FAKE), au moins un point : voir {@link #namesNonexistentIdentifier}. */
    private static final Pattern DOTTED_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+");

    /**
     * Vrai si {@code question} nomme explicitement un identifiant T24 au format pointé (ex: "LC.DOCUMENT.FAKE")
     * qui n'existe RÉELLEMENT ni comme table ni comme colonne en base (vérifié, pas seulement détecté par forme) :
     * dans ce cas, aucune "connaissance générale" ne peut légitimement s'appliquer — un nom interne précis
     * inexistant ne peut être que deviné/inventé (observé en pratique) — donc le repli LLM ne doit pas être tenté
     * pour ce nom précis. Si au moins un identifiant nommé existe réellement (table ou colonne), même si
     * l'information demandée à son sujet manque, le repli connaissances générales reste autorisé.
     * <p>Si {@code module} est fourni, un identifiant préfixé par ce code module (ex. "AC.ACCOUNT" en module AC)
     * est aussi vérifié SANS ce préfixe (ex. "ACCOUNT") avant d'être déclaré inexistant : le préfixe "MODULE."
     * n'est souvent qu'une façon naturelle de désigner "la table X du module déjà sélectionné", pas le vrai nom
     * pointé de la table/colonne en base — sans cette tolérance, "AC.ACCOUNT" était déclaré à tort inexistant
     * alors que la vraie table s'appelle simplement ACCOUNT (le module AC est déjà implicite).
     */
    /** Message de court-circuit (sans FAISS ni LLM) si la question nomme un identifiant pointé introuvable, ou une
     *  table qui existe mais appartient à un AUTRE module que celui sélectionné ; {@code null} sinon. */
    private String blockedIdentifierMessage(String question, String module) {
        if (namesNonexistentIdentifier(question, module)) {
            return "Je ne trouve aucune table ou colonne correspondant exactement à ce nom dans la "
                    + "documentation T24 indexée. Vérifiez l'orthographe, ou reformulez votre question.";
        }
        if (question == null || module == null || module.isBlank()) return null;
        Matcher m = DOTTED_TOKEN_PATTERN.matcher(question);
        while (m.find()) {
            // Module lu dans DOCUMENT (colonne simple) et non via TableT24.getModule() (relation paresseuse) : ce
            // code s'exécute aussi dans le thread de streaming, sans session Hibernate ouverte.
            var docs = documentRepository.findByTableNameIgnoreCase(m.group());
            if (docs.isEmpty()) continue;
            String ownerCode = docs.get(0).getModule();
            if (ownerCode == null || ownerCode.equalsIgnoreCase(module.trim())) continue;
            String ownerLabel = moduleRepository.findByNomModuleIgnoreCase(ownerCode).map(ModuleT24::getLabel).orElse(null);
            String label = ownerLabel != null && !ownerLabel.isBlank() ? " (" + ownerLabel + ")" : "";
            return "La table " + docs.get(0).getTableName() + " appartient au module " + ownerCode + label
                    + " et non au module actuellement sélectionné (" + module.trim().toUpperCase()
                    + "). Sélectionnez le module " + ownerCode + " pour la consulter.";
        }
        return null;
    }

    private boolean namesNonexistentIdentifier(String question, String module) {
        if (question == null || question.isBlank()) return false;
        String modulePrefix = module != null && !module.isBlank() ? module.trim().toUpperCase() + "." : null;
        Matcher m = DOTTED_TOKEN_PATTERN.matcher(question);
        boolean foundAny = false;
        while (m.find()) {
            foundAny = true;
            String token = m.group();
            if (identifierExists(token)) return false;
            if (modulePrefix != null && token.toUpperCase().startsWith(modulePrefix)
                    && token.length() > modulePrefix.length()
                    && identifierExists(token.substring(modulePrefix.length()))) {
                return false;
            }
            // Inversement, un nom cité SANS le préfixe module ("free.message" pour la vraie table
            // "EB.FREE.MESSAGE" en module EB) désigne bien cette table.
            if (modulePrefix != null && identifierExists(modulePrefix + token)) return false;
            // Faute de frappe proche d'un vrai nom de table (ex. "MD.PARAMTER" pour "MD.PARAMETER") : déjà toléré
            // pour un nom en MAJUSCULES sans point (voir plus bas, tableExistsFuzzy), mais pas pour cette forme
            // pointée — bug trouvé en pratique, la question était bloquée alors que la table existe réellement.
            if (tableExistsFuzzy(token, module)) return false;
        }

        // Nom de table tapé SANS point (ex: "la description de la table XXXX ?") : un identifiant pointé
        // (ci-dessus) n'est pas la seule façon de désigner explicitement une table précise. Signature retenue :
        // un mot tout en MAJUSCULES collé juste après "table"/"tables" (voir ALLCAPS_TABLE_NAME_PROXIMITY) — un
        // mot ordinaire de la phrase française est presque toujours en minuscules, et une proximité quasi nulle
        // (juste un espace) évite de confondre avec un acronyme métier mentionné plus loin dans la question (ex.
        // "quelle table gère le SWIFT ?", où SWIFT n'est pas immédiatement accolé à "table" : reste en recherche
        // sémantique normale, jamais court-circuité ici).
        List<Integer> tableWordEnds = new ArrayList<>();
        Matcher tw = TABLE_WORD_PATTERN.matcher(question);
        while (tw.find()) tableWordEnds.add(tw.end());
        if (!tableWordEnds.isEmpty()) {
            Matcher caps = ALLCAPS_WORD_PATTERN.matcher(question);
            while (caps.find()) {
                String token = caps.group();
                int idx = caps.start();
                boolean immediatelyAfterTableWord = tableWordEnds.stream()
                        .anyMatch(end -> idx >= end && idx - end <= ALLCAPS_TABLE_NAME_PROXIMITY);
                if (!immediatelyAfterTableWord) continue;
                foundAny = true;
                if (tableExistsFuzzy(token, module)) return false;
                // Nom cité SANS le préfixe module ("la table CARRIER" pour la vraie table "DE.CARRIER" en module
                // DE) : même tolérance que pour la forme pointée ci-dessus — sans elle, l'écart (le préfixe
                // manquant) dépasse la tolérance de FuzzyMatch et la question est bloquée à tort (bug trouvé en
                // pratique, "la table carrier" alors que "la table de.carrier" fonctionne).
                if (modulePrefix != null && identifierExists(modulePrefix + token)) return false;
            }
        }
        return foundAny;
    }

    /** Mot tout en MAJUSCULES (au moins 3 caractères) : voir {@link #namesNonexistentIdentifier}. */
    private static final Pattern ALLCAPS_WORD_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]{2,}\\b");

    /** Écart maximal (en caractères) toléré entre "table"/"tables" et le mot en MAJUSCULES qui le suit pour être
     *  considéré comme le nom explicitement désigné (juste un espace ou une ponctuation légère, ex. "table : X") —
     *  volontairement bien plus strict que {@link #TABLE_WORD_PROXIMITY} (utilisé pour la résolution normale, plus
     *  tolérante) afin de ne jamais confondre un acronyme métier cité plus loin dans la phrase avec le nom de la
     *  table elle-même. */
    private static final int ALLCAPS_TABLE_NAME_PROXIMITY = 5;

    /** Vrai si {@code token} correspond, exactement ou à une faute de frappe près, au nom réel d'une table —
     *  scopée au module si fourni, pour la même tolérance que {@link #resolveInlineTableName}. */
    private boolean tableExistsFuzzy(String token, String module) {
        if (tableRepository.findByNomTableIgnoreCase(token).isPresent()) return true;
        if (module == null || module.isBlank()) return false;
        for (TableT24 t : tableRepository.findByModule_NomModuleIgnoreCase(module)) {
            if (FuzzyMatch.isCloseEnough(token, t.getNomTable())) return true;
        }
        return false;
    }

    private boolean identifierExists(String token) {
        boolean tableExists = tableRepository.findByNomTableIgnoreCase(token).isPresent();
        boolean columnExists = !documentRepository.findByColumnNameIgnoreCase(token).isEmpty();
        return tableExists || columnExists;
    }

    /** Le mot "table(s)", qui doit précéder de près un nom pour qu'il soit reconnu comme une référence explicite
     *  à cette table : voir {@link #resolveInlineTableName}. */
    private static final Pattern TABLE_WORD_PATTERN = Pattern.compile("(?iu)\\btables?\\b");

    /** "dans account", "dans la table account" : le mot qui suit "dans" (voir {@link #resolveInlineTableName}). */
    private static final Pattern IN_TABLE_PATTERN =
            Pattern.compile("(?iu)\\bdans\\s+(?:la\\s+table\\s+)?([A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)*)");

    /** Distance maximale (en caractères) entre le mot "table" et le nom qui le suit, pour qu'il soit accepté
     *  comme une référence explicite plutôt qu'une coïncidence de langage naturel (voir {@link #resolveInlineTableName}). */
    private static final int TABLE_WORD_PROXIMITY = 30;

    /**
     * Position (dans {@code qUpper}) de la première occurrence de {@code name} qui suit d'assez près un mot
     * "table"/"tables" (voir {@code tableWordEnds}, positions de fin de ces mots) — {@code null} si aucune
     * occurrence ne remplit cette condition. Sans cette contrainte de proximité, un nom de table court et courant
     * (ex: "TELLER", qui est aussi le terme générique du module TT) matcherait n'importe quelle question qui le
     * mentionne comme mot ordinaire (ex: "la Home Page Teller"), pas comme référence à la table — observé en
     * pratique : la table TT.TELLER.HP.VERSIONS jamais considérée à cause de ce faux positif sur "TELLER" seul.
     */
    private static Integer indexNearTableWord(String qUpper, String name, List<Integer> tableWordEnds) {
        int idx = qUpper.indexOf(name);
        while (idx >= 0) {
            for (int end : tableWordEnds) {
                if (idx >= end && idx - end <= TABLE_WORD_PROXIMITY) return idx;
            }
            idx = qUpper.indexOf(name, idx + 1);
        }
        return null;
    }

    private String resolveInlineTableName(String question, String module, String explicitTableName) {
        if (explicitTableName != null && !explicitTableName.isBlank()) return explicitTableName;
        if (module == null || module.isBlank() || question == null || question.isBlank()) return explicitTableName;
        String qUpper = question.toUpperCase();
        String modulePrefix = module.trim().toUpperCase() + ".";
        List<TableT24> tables = tableRepository.findByModule_NomModuleIgnoreCase(module);

        // Un nom de table POINTÉ (au moins deux segments, ex: "eb.free.message") cité dans la question désigne
        // sans ambiguïté cette table précise, même sans le mot "table" à proximité — contrairement à un nom court
        // et non pointé (ex: "TELLER"), qui reste soumis à la contrainte de proximité ci-dessous pour éviter un
        // faux positif sur un mot ordinaire. Sans cette vérification, "narrative dans eb.free.message ?" restait
        // scopé à tout le module EB au lieu de la seule table nommée, et remontait toute autre table possédant
        // aussi un champ NARRATIVE.
        // "dans account" / "dans md.beneficiary" / "dans la table account" : le mot qui suit "dans" désigne la
        // table quand il en est le nom EXACT (avec ou sans préfixe module) — jamais une colonne homonyme d'une
        // autre table (ex. "identifie un compte dans account" renvoyait les colonnes ACCOUNT d'autres tables) —
        // ou, à défaut, un nom très proche (faute de frappe : "acount" -> ACCOUNT). Prioritaire sur les autres
        // noms pointés de la question, qui peuvent être des champs homonymes d'une table (ex. MD.DEAL, champ de
        // MD.BENEFICIARY mais aussi table à part entière).
        Matcher inTable = IN_TABLE_PATTERN.matcher(question);
        while (inTable.find()) {
            String word = inTable.group(1);
            if (Set.of("quelle", "quel", "quels", "quelles", "cette", "ce", "cet", "ces", "la", "le", "les", "une",
                    "un", "table", "tables").contains(word.toLowerCase())) continue;
            for (TableT24 t : tables) {
                String name = t.getNomTable();
                String stripped = name.toUpperCase().startsWith(modulePrefix) ? name.substring(modulePrefix.length()) : name;
                if (name.equalsIgnoreCase(word) || stripped.equalsIgnoreCase(word)) return name;
            }
            if (word.length() >= 5) {
                for (TableT24 t : tables) {
                    String name = t.getNomTable();
                    String stripped = name.toUpperCase().startsWith(modulePrefix) ? name.substring(modulePrefix.length()) : name;
                    if (FuzzyMatch.isCloseEnough(word, name) || FuzzyMatch.isCloseEnough(word, stripped)) return name;
                }
            }
        }

        Matcher dotted = DOTTED_TOKEN_PATTERN.matcher(qUpper);
        while (dotted.find()) {
            String token = dotted.group();
            // Un nom précédé de "champ"/"colonne" est un champ, pas la table (même s'il existe aussi comme table).
            String before = qUpper.substring(Math.max(0, dotted.start() - 12), dotted.start());
            if (before.matches("(?s).*\\b(CHAMP|COLONNE)\\s+$")) continue;
            for (TableT24 t : tables) {
                if (t.getNomTable().equalsIgnoreCase(token) || t.getNomTable().equalsIgnoreCase(modulePrefix + token)) {
                    return t.getNomTable();
                }
            }
        }

        List<Integer> tableWordEnds = new ArrayList<>();
        Matcher tw = TABLE_WORD_PATTERN.matcher(question);
        while (tw.find()) tableWordEnds.add(tw.end());
        if (tableWordEnds.isEmpty()) return explicitTableName;

        for (TableT24 t : tables) {
            String name = t.getNomTable().toUpperCase();
            String stripped = name.startsWith(modulePrefix) ? name.substring(modulePrefix.length()) : name;
            if (indexNearTableWord(qUpper, name, tableWordEnds) != null
                    || (stripped.length() >= 4 && indexNearTableWord(qUpper, stripped, tableWordEnds) != null)) {
                return t.getNomTable();
            }
        }

        // Repli tolérant aux fautes de frappe (ex: "ACOUNTS.CLOSURE" pour "ACCOUNT.CLOSURE") : uniquement si
        // aucune correspondance exacte n'a été trouvée, pour ne jamais préférer une approximation à un vrai nom
        // déjà présent tel quel dans la question. Même contrainte de proximité avec "table" : on ne tente la
        // comparaison floue que sur les tokens qui en sont assez proches.
        Matcher tokenMatcher = IDENTIFIER_TOKEN_PATTERN.matcher(question);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group();
            if (token.length() < 6) continue;
            int tokenIdx = tokenMatcher.start();
            boolean nearTableWord = tableWordEnds.stream()
                    .anyMatch(end -> tokenIdx >= end && tokenIdx - end <= TABLE_WORD_PROXIMITY);
            if (!nearTableWord) continue;
            for (TableT24 t : tables) {
                String name = t.getNomTable().toUpperCase();
                String stripped = name.startsWith(modulePrefix) ? name.substring(modulePrefix.length()) : name;
                if (FuzzyMatch.isCloseEnough(token, name) || (stripped.length() >= 4 && FuzzyMatch.isCloseEnough(token, stripped))) {
                    return t.getNomTable();
                }
            }
        }
        return explicitTableName;
    }

    private static final Pattern DOTTED_IN_ANSWER = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+\\b");

    /**
     * Corrige un identifiant T24 "traduit" ou déformé par le modèle (observé : « LD.SCHEDULE.DEFINE » écrit
     * « LD.SCHEDULE.DEFINIR ») : un identifiant pointé de la réponse qui n'existe ni dans le contexte ni dans la
     * base, mais qui est très proche d'un identifiant du contexte, est remplacé par celui-ci. Aucun autre
     * identifiant n'est modifié.
     */
    /** Voir {@link #repairIdentifiers} : "TT.TT.TELLER.HP.VERSIONS" -> "TT.TELLER.HP.VERSIONS" ; inchangé si le
     *  premier segment n'est pas répété (fonction pure, sans accès base, pour rester testable isolément). */
    static String collapseRepeatedPrefix(String token) {
        return token.replaceFirst("^([A-Z0-9]+)\\.\\1\\.", "$1.");
    }

    private String repairIdentifiers(String answer, List<RetrieverService.ScoredDocument> context) {
        if (answer == null || answer.isBlank() || context == null || context.isEmpty()) return answer;
        Set<String> known = new HashSet<>();
        for (RetrieverService.ScoredDocument r : context) {
            Document d = r.document();
            known.add(d.getTableName().toUpperCase());
            if (d.getColumnName() != null) known.add(d.getColumnName().toUpperCase());
            Matcher m = DOTTED_IN_ANSWER.matcher(d.getContent());
            while (m.find()) known.add(m.group());
        }
        String repaired = answer;
        Set<String> seen = new HashSet<>();
        Matcher m = DOTTED_IN_ANSWER.matcher(answer);
        while (m.find()) {
            String token = m.group();
            if (known.contains(token) || !seen.add(token)) continue;
            if (!documentRepository.findByTableNameIgnoreCase(token).isEmpty()) continue;
            if (!documentRepository.findByColumnNameIgnoreCase(token).isEmpty()) continue;
            // Premier segment répété deux fois de suite (ex. "TT.TT.TELLER.HP.VERSIONS" pour le vrai
            // "TT.TELLER.HP.VERSIONS") : le petit modèle local double parfois le code du module en tête d'un
            // identifiant qu'il recopie par ailleurs correctement plus loin (observé en pratique, question
            // MODULE_GENERAL) — vérification mécanique directe, prioritaire sur le rapprochement flou générique
            // ci-dessous (qui peut échouer selon la tolérance de distance).
            String dedupedPrefix = collapseRepeatedPrefix(token);
            String fix = !dedupedPrefix.equals(token) && known.contains(dedupedPrefix) ? dedupedPrefix : null;
            // Identifiant du contexte déformé (lettre changée) ou tronqué en fin de génération (préfixe).
            if (fix == null) {
                fix = known.stream()
                        .filter(k -> k.contains(".")
                                && (FuzzyMatch.isCloseEnough(token, k) || (token.length() >= 8 && k.startsWith(token))))
                        .findFirst().orElse(null);
            }
            if (fix != null) {
                log.info("Identifiant corrigé dans la réponse : {} -> {}", token, fix);
                repaired = repaired.replace(token, fix);
            }
        }
        return repaired;
    }

    /** Dernier recours d'une réponse à contexte déterministe quand le modèle n'a produit aucune réponse fiable :
     *  un message français qui renvoie vers les éléments documentés (jamais un texte source anglais brut). */
    private static String specialFallback(List<RetrieverService.ScoredDocument> results) {
        String items = results.stream()
                .map(r -> r.document())
                .filter(d -> !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(d.getColumnName()))
                .map(d -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName())
                        ? d.getTableName() : d.getColumnName() + " (" + d.getTableName() + ")")
                .distinct().limit(6).collect(Collectors.joining(", "));
        return "Je n'ai pas pu formuler de synthèse fiable pour cette demande à partir de la documentation. "
                + (items.isEmpty() ? "" : "Les éléments documentés concernés sont : " + items + ". ")
                + "Ouvrez « Voir la source » pour consulter le texte de la documentation, ou reformulez votre question.";
    }

    /** Nombre maximal de champs listés dans le tableau ajouté à une réponse sur une table. */
    private static final int FIELDS_TABLE_MAX_ROWS = 15;

    /**
     * Pour une question sur une TABLE (la vue d'ensemble de la table est le premier résultat) : ajoute à la réponse un
     * tableau Markdown des champs construit directement depuis la base (nom, type, saisie) — jamais généré par le
     * modèle, donc sans risque d'invention. Vide dans tous les autres cas.
     */
    private String fieldsTableFor(List<RetrieverService.ScoredDocument> results, boolean useSpecial, String answer) {
        if (useSpecial || results == null || results.isEmpty() || answer == null || answer.isBlank()) return "";
        Document first = results.get(0).document();
        if (!Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(first.getColumnName())) return "";
        if (isMainlyUncertain(answer)) return "";
        List<Document> fields = documentRepository.findByTableNameIgnoreCase(first.getTableName()).stream()
                .filter(d -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        if (fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n### Champs de la table ").append(first.getTableName()).append("\n\n");
        sb.append("| Champ | Type | Saisie |\n| --- | --- | --- |\n");
        for (Document d : fields.subList(0, Math.min(FIELDS_TABLE_MAX_ROWS, fields.size()))) {
            ColumnResultDto c = toColumnResult(d);
            String type = c.dataType() == null || c.dataType().isBlank() ? "-" : c.dataType();
            sb.append("| ").append(d.getColumnName()).append(" | ").append(type).append(" | ")
                    .append(mandatoryLabelFor(c)).append(" |\n");
        }
        if (fields.size() > FIELDS_TABLE_MAX_ROWS) {
            sb.append("\nLes ").append(FIELDS_TABLE_MAX_ROWS).append(" premiers champs sur ").append(fields.size())
                    .append(" sont affichés ; posez une question sur un champ précis pour en savoir plus.");
        }
        return sb.toString().stripTrailing();
    }

    /** Nombre de vues d'ensemble de tables données au modèle pour une question générale sur un module. */
    private static final int MODULE_OVERVIEW_TABLES = 6;

    /**
     * Contexte documentaire déterministe pour les questions qui ne se résolvent pas bien par une recherche de champs :
     * <ul>
     *   <li>question générale sur un module : synthèse du module + vues d'ensemble de tables (jamais de champs
     *       arbitraires, qui font dériver la réponse vers un détail sans rapport avec la question) ;</li>
     *   <li>question de suivi ("Explique davantage", "Un exemple ?", "Et les règles de validation ?") : on reste sur
     *       les tables/champs cités par la réponse précédente (sources persistées), au lieu de relancer une recherche
     *       sémantique sur une phrase de trois mots.</li>
     * </ul>
     * Liste vide si aucun de ces cas ne s'applique : la recherche classique prend alors le relais.
     */
    private List<RetrieverService.ScoredDocument> specialContext(QuestionIntent intent, String question, String module,
                                                                  List<ChatHistory> prior) {
        boolean moduleSet = module != null && !module.isBlank();
        if (intent == QuestionIntent.MODULE_GENERAL) {
            return moduleSet
                    ? retrieverService.searchModuleOverview(question, module, MODULE_OVERVIEW_TABLES, Set.of())
                    : List.of();
        }
        if (intent == QuestionIntent.TOPIC_TABLES) {
            return moduleSet ? topicTablesContext(question, module) : List.of();
        }
        if (!intent.isFollowUp()) return List.of();

        List<SourceFieldDto> previous = previousSources(prior);
        if (previous.isEmpty()) return List.of();
        List<String> tables = previous.stream().map(SourceFieldDto::tableName).distinct().toList();

        // Réponse précédente centrée sur des champs précis : on reste sur ces champs.
        List<RetrieverService.ScoredDocument> fields = new ArrayList<>();
        for (SourceFieldDto s : previous) {
            if (s.columnName() == null || s.columnName().isBlank()) continue;
            documentRepository.findByColumnNameIgnoreCaseAndTableNameIgnoreCase(s.columnName(), s.tableName())
                    .stream().findFirst().ifPresent(d -> fields.add(new RetrieverService.ScoredDocument(d, 1.0)));
            if (fields.size() >= 3) break;
        }
        if (!fields.isEmpty()) return fields;

        // Réponse précédente centrée sur des tables (vues d'ensemble).
        switch (intent) {
            case FOLLOWUP_VALIDATION -> {
                List<RetrieverService.ScoredDocument> rules = retrieverService.validationDocuments(tables, 4);
                if (rules.size() < 2 && moduleSet) {
                    List<String> moduleTables = documentRepository.findDistinctTableNamesByModuleIgnoreCase(module)
                            .stream().sorted().toList();
                    rules = retrieverService.validationDocuments(moduleTables, 4);
                }
                return rules.isEmpty()
                        ? retrieverService.documentsOfTables(tables.subList(0, Math.min(2, tables.size())), 0)
                        : rules;
            }
            case FOLLOWUP_EXAMPLE -> {
                // Exemple réellement documenté (Description d'un champ des tables discutées, à défaut du module).
                List<RetrieverService.ScoredDocument> examples = retrieverService.exampleDocuments(tables, 2);
                if (examples.isEmpty() && moduleSet) {
                    examples = retrieverService.exampleDocuments(documentRepository
                            .findDistinctTableNamesByModuleIgnoreCase(module).stream().sorted().toList(), 2);
                }
                return examples.isEmpty()
                        ? retrieverService.documentsOfTables(tables.subList(0, Math.min(3, tables.size())), 2)
                        : examples;
            }
            case FOLLOWUP_MORE -> {
                List<RetrieverService.ScoredDocument> out = new ArrayList<>(
                        retrieverService.documentsOfTables(tables.subList(0, Math.min(2, tables.size())), 2).stream()
                                .filter(r -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(r.document().getColumnName()))
                                .toList());
                if (moduleSet) {
                    Set<String> covered = tables.stream().map(String::toUpperCase).collect(Collectors.toSet());
                    retrieverService.searchModuleOverview(question, module, 2, covered).stream()
                            .filter(r -> !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(r.document().getColumnName()))
                            .forEach(out::add);
                }
                return out;
            }
            default -> {
                return retrieverService.documentsOfTables(tables.subList(0, Math.min(3, tables.size())), 2);
            }
        }
    }

    /** Nombre max. de tables retenues pour une question thématique ("tables qui contiennent des informations sur X ?"). */
    private static final int TOPIC_TABLES_COUNT = 5;
    /** Nombre max. de champs donnés par table retenue, pour que le LLM puisse citer les "champs principaux" réels. */
    private static final int TOPIC_TABLES_FIELDS_PER_TABLE = 5;

    /**
     * Contexte d'une question TOPIC_TABLES ("quelles tables du module contiennent des informations sur X ? pour
     * chacune, rôle et champs principaux") : classe les vues d'ensemble de tables du module par proximité
     * sémantique avec la question (même mécanisme que {@link RetrieverService#searchModuleOverview}, qui sert
     * aussi à MODULE_GENERAL, mais ici SANS la synthèse du module — le sujet n'est pas le module dans son
     * ensemble, seulement le sous-ensemble de tables lié au thème demandé), puis ajoute quelques champs réels de
     * chacune de ces tables pour que le LLM puisse citer des "champs principaux" réellement documentés.
     */
    private List<RetrieverService.ScoredDocument> topicTablesContext(String question, String module) {
        List<RetrieverService.ScoredDocument> ranked = retrieverService
                .searchModuleOverview(question, module, TOPIC_TABLES_COUNT, Set.of()).stream()
                .filter(r -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(r.document().getColumnName()))
                .toList();
        if (ranked.isEmpty()) return List.of();
        List<String> tableNames = ranked.stream().map(r -> r.document().getTableName()).distinct().toList();
        List<RetrieverService.ScoredDocument> out = new ArrayList<>(ranked);
        retrieverService.documentsOfTables(tableNames, TOPIC_TABLES_FIELDS_PER_TABLE).stream()
                .filter(r -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(r.document().getColumnName()))
                .forEach(out::add);
        return out;
    }

    /** Sources (tables/champs) de la dernière réponse de l'assistant qui en a, vides si aucune. */
    private List<SourceFieldDto> previousSources(List<ChatHistory> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatHistory h = history.get(i);
            if (!"reponse".equalsIgnoreCase(h.getMessageType())) continue;
            List<SourceFieldDto> sources = deserializeSourceFields(h.getSourceColumns());
            return sources.stream().filter(s -> s.tableName() != null).toList();
        }
        return List.of();
    }

    /** Derniers échanges du fil (4 max, questions+réponses), formatés pour le prompt LLM. Vide si aucun historique. */
    private static String recentHistoryText(List<ChatHistory> history) {
        if (history.isEmpty()) return "";
        int from = Math.max(0, history.size() - 8);
        return history.subList(from, history.size()).stream()
                .map(h -> "question".equalsIgnoreCase(h.getMessageType())
                        ? "Utilisateur : " + h.getContent()
                        : "Assistant : " + abbreviateAnswer(h.getContent()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Historique réduit aux seules questions précédentes de l'utilisateur, pour une question de suivi dont le contexte
     * documentaire est déterministe (voir {@link #specialContext}) : voir le texte de sa réponse précédente pousse le
     * modèle local à le recopier (rappel du module, description déjà donnée) au lieu de traiter la nouvelle demande.
     */
    private static String questionsOnlyHistory(List<ChatHistory> history) {
        String questions = history.stream()
                .filter(h -> "question".equalsIgnoreCase(h.getMessageType()))
                .skip(Math.max(0, history.stream().filter(h -> "question".equalsIgnoreCase(h.getMessageType())).count() - 3))
                .map(h -> "Utilisateur : " + h.getContent())
                .collect(Collectors.joining("\n"));
        return questions.isEmpty() ? "" : questions
                + "\n(L'assistant a déjà répondu à ces questions ; ne répète pas ses réponses.)";
    }

    /** Longueur maximale d'une réponse précédente recopiée dans le prompt. */
    private static final int HISTORY_ANSWER_MAX_CHARS = 350;

    /**
     * Les réponses précédentes sont abrégées dans le prompt : un petit modèle local à qui l'on montre en entier sa
     * réponse précédente la recopie presque telle quelle en réponse à "Explique davantage" ou "Un exemple ?"
     * (répétition observée). Le début suffit à savoir de quoi on parlait ; la documentation vient du contexte.
     */
    private static String abbreviateAnswer(String answer) {
        if (answer == null) return "";
        String flat = answer.replaceAll("(?m)^\\|.*$", "").replaceAll("\\s+", " ").trim();
        return flat.length() <= HISTORY_ANSWER_MAX_CHARS ? flat : flat.substring(0, HISTORY_ANSWER_MAX_CHARS) + " [...]";
    }

    /**
     * Uniquement le dernier échange (question+réponse) du fil, pour la reformulation de requête
     * ({@code rewriteQuery}) — volontairement plus réduit que {@link #recentHistoryText} (8 messages) : un petit
     * modèle local peut confondre le sujet d'une question de suivi avec un sujet plus ancien encore présent dans
     * une fenêtre d'historique plus large (observé en pratique), alors qu'une question de suivi ("il est
     * obligatoire ?") se rapporte presque toujours à l'échange immédiatement précédent.
     */
    /** Refus en anglais du petit modèle local malgré la consigne de répondre en français (voir
     *  AnswerServiceImpl#looksLikeEnglish) : signale, comme {@link #UNCERTAIN_ANSWER_PATTERN}, un échange qui
     *  n'apporte en réalité aucune information exploitable. */
    private static final java.util.regex.Pattern ENGLISH_REFUSAL_PATTERN = java.util.regex.Pattern.compile(
            "(?iu)\\bi'm sorry\\b|\\bi cannot\\b|\\bi can't\\b|\\bwithout more information\\b|"
                    + "\\bi don't have\\b|\\bi do not have\\b");

    private static boolean isUninformativeAnswer(String answer) {
        return answer != null
                && (UNCERTAIN_ANSWER_PATTERN.matcher(answer).find() || ENGLISH_REFUSAL_PATTERN.matcher(answer).find());
    }

    private static String lastExchangeText(List<ChatHistory> history) {
        if (history.isEmpty()) return "";
        // Si le dernier échange n'a lui-même apporté aucune information exploitable (réponse incertaine, ou
        // refus en anglais du petit modèle), il ne doit jamais servir d'ancre pour résoudre une question de
        // suivi elliptique ("ce champ", "il...") : on remonte alors à l'échange substantiel précédent, qui porte
        // le vrai sujet établi plus tôt dans la conversation (observé en pratique : une question répétée après un
        // premier échec perdait complètement ce sujet et finissait par accrocher un champ sans rapport).
        int end = history.size();
        int fallbackEnd = end;
        while (end >= 2 && isUninformativeAnswer(history.get(end - 1).getContent())) {
            end -= 2;
        }
        if (end < 2) end = fallbackEnd;
        int from = Math.max(0, end - 2);
        return history.subList(from, end).stream()
                .map(h -> ("question".equalsIgnoreCase(h.getMessageType()) ? "Utilisateur : " : "Assistant : ") + h.getContent())
                .collect(Collectors.joining("\n"));
    }

    /** Dernière question posée par l'utilisateur dans ce fil, utilisée pour ancrer la recherche d'une question de suivi. */
    private static String lastUserQuestion(List<ChatHistory> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("question".equalsIgnoreCase(history.get(i).getMessageType())) return history.get(i).getContent();
        }
        return null;
    }

    /**
     * Le caractère obligatoire est reclassé dynamiquement depuis la Description à chaque affichage (voir
     * {@link MandatoryClassifier}), jamais lu tel quel depuis la ligne "Obligatoire : " stockée en base : ainsi
     * toute amélioration de cette classification s'applique immédiatement à tous les champs, sans réindexation.
     * "NON SPÉCIFIÉ" (aucune info trouvée dans le HelpText) est affiché distinctement d'un vrai "NON" (optionnel
     * confirmé), pour ne jamais faire passer une absence d'information pour une réponse "optionnel" certaine.
     */
    private static ColumnResultDto toColumnResult(Document doc) {
        String content = doc.getContent();
        String dataType = extractField(content, "Type : ");
        String description = extractDescription(content);
        String rawMandatory = MandatoryClassifier.classify(description);
        boolean mandatory = "OUI".equalsIgnoreCase(rawMandatory);
        String inputType;
        if (mandatory) inputType = "MAN";
        else if (rawMandatory.toUpperCase().startsWith("NON (AUTO")) inputType = "NOINP";
        else if ("NON SPÉCIFIÉ".equalsIgnoreCase(rawMandatory)) inputType = "UNSPEC";
        else inputType = "";
        return new ColumnResultDto(doc.getColumnName(), doc.getTableName(), doc.getModule(),
                dataType, inputType, mandatory, description, true, null);
    }

    /**
     * Ne garde, parmi les colonnes retrouvées pour la recherche, que celles réellement citées (par leur nom) dans
     * la réponse finale du LLM. Sans ce filtre, le seuil de confiance volontairement permissif à l'échelle du
     * module (pour laisser passer les questions reformulées, ou lister plusieurs champs d'une même table) laisse
     * remonter jusqu'à {@code topK} candidats qui restent affichés en carte même quand seuls un ou deux sont
     * vraiment pertinents pour la question précise (observé en pratique : SERIAL.NO, DRAWER.IBAN affichés pour
     * une question sur "le code SWIFT", sans rapport). Repli sur la liste complète si aucune colonne n'est
     * littéralement citée dans la réponse (ex. réponse plus généraliste sans reformuler les noms).
     */
    private static List<ColumnResultDto> columnsMentionedInAnswer(List<RetrieverService.ScoredDocument> results, String answer) {
        List<RetrieverService.ScoredDocument> realColumns = results.stream()
                .filter(r -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(r.document().getColumnName()))
                .filter(r -> !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(r.document().getColumnName()))
                .toList();
        if (answer == null || answer.isBlank()) {
            return realColumns.stream().map(r -> toColumnResult(r.document())).toList();
        }
        String answerUpper = answer.toUpperCase();
        List<RetrieverService.ScoredDocument> mentioned = realColumns.stream()
                .filter(r -> answerUpper.contains(r.document().getColumnName().toUpperCase()))
                .toList();
        return (mentioned.isEmpty() ? realColumns : mentioned).stream().map(r -> toColumnResult(r.document())).toList();
    }

    /**
     * Construit les entrées de la carte "Voir la source" directement à partir des résultats retrouvés (pas de
     * {@link #columnsMentionedInAnswer}, qui exclut la vue d'ensemble de table — ici au contraire indispensable :
     * une réponse de niveau table ne cite jamais littéralement "__TABLE_OVERVIEW__", donc le filtre "cité dans la
     * réponse" ne peut de toute façon jamais la retenir positivement ; seul le repli sur la liste complète doit
     * l'afficher). Table, Champ et Description sont TOUJOURS indiqués (Champ omis pour une vue d'ensemble de
     * table) — la Description reste visible même sans avoir été explicitement demandée : c'est elle qui permet à
     * l'utilisateur de vérifier lui-même, à partir du texte source, si la réponse du chatbot est fidèle ou non
     * (observé en pratique : sans elle, impossible de juger si le champ retrouvé répond vraiment à la question).
     * Seuls Saisie/Contrôle de saisie restent conditionnés à l'intention détectée dans la question — ce sont des
     * détails secondaires, pas la base de vérification de la réponse.
     */
    private static List<SourceFieldDto> buildSourceFields(List<RetrieverService.ScoredDocument> results, String answer,
                                                            String question) {
        if (results == null || results.isEmpty()) return List.of();
        boolean askedMandatory = question != null && ASKS_MANDATORY_PATTERN.matcher(question).find();
        boolean askedValidation = question != null && ASKS_VALIDATION_PATTERN.matcher(question).find();

        String answerUpper = answer == null ? "" : answer.toUpperCase();
        List<RetrieverService.ScoredDocument> mentioned = results.stream()
                .filter(r -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(r.document().getColumnName()))
                .filter(r -> answerUpper.contains(r.document().getColumnName().toUpperCase()))
                .toList();
        List<RetrieverService.ScoredDocument> chosen = mentioned.isEmpty() ? results : mentioned;

        List<SourceFieldDto> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RetrieverService.ScoredDocument r : chosen) {
            Document doc = r.document();
            if (Document.MODULE_SUMMARY_COLUMN_MARKER.equals(doc.getColumnName())) continue;
            if (!seen.add(doc.getTableName() + "|" + doc.getColumnName())) continue;
            if (Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(doc.getColumnName())) {
                out.add(new SourceFieldDto(doc.getTableName(), null, null, extractDescription(doc.getContent()), null,
                        doc.getModule(), xmlFileNameFor(doc.getTableName())));
            } else {
                ColumnResultDto c = toColumnResult(doc);
                out.add(new SourceFieldDto(c.tableName(), c.columnName(),
                        askedMandatory ? mandatoryLabelFor(c) : null,
                        c.description(),
                        askedValidation ? extractValidationRule(c.description()) : null,
                        c.module(), xmlFileNameFor(c.tableName())));
            }
        }
        return out;
    }

    /**
     * Pour une question d'ensemble/d'identification de table (voir {@link #isOverviewQuestion}) : la carte
     * "Voir la source" montre seulement la ou les tables réellement consultées, sans aucune colonne — afficher
     * les colonnes du meilleur candidat FAISS donnerait l'impression trompeuse que ces champs précis sont "la
     * réponse", alors que la réponse porte sur la table elle-même (jamais citée nommément par colonne). La
     * description de la table est TOUJOURS incluse quand disponible (voir {@link #buildSourceFields} : elle sert
     * à vérifier la réponse, pas seulement à répondre à une intention "description" détectée dans la question).
     */
    /** Une entrée du texte de dernier recours (quand le LLM échoue) : "CHAMP (TABLE) : description", en français
     *  quand la traduction en cache existe (voir Document#descriptionFr), sans balise Markdown. */
    private static String rawFallbackEntry(RetrieverService.ScoredDocument r) {
        Document doc = r.document();
        String fr = doc.getDescriptionFr();
        String description = fr != null && !fr.isBlank() ? fr : extractDescription(doc.getContent());
        String label = Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(doc.getColumnName())
                ? "Table " + doc.getTableName()
                : doc.getColumnName() + " (" + doc.getTableName() + ")";
        return label + " : " + description;
    }

    /** Garde les résultats au-dessus du seuil de confiance ; quand la recherche est déjà limitée à UNE table connue
     *  et que le seuil n'en laisse que 1 ou 2, garde les meilleurs résultats de cette table (ex. "quels sont les
     *  principaux champs de la table X" : formulation générique, scores faibles mais tous les champs sont de la
     *  bonne table — sinon le contexte est trop pauvre pour répondre). */
    private static List<RetrieverService.ScoredDocument> filterByConfidence(
            List<RetrieverService.ScoredDocument> raw, double threshold, boolean tableScoped) {
        List<RetrieverService.ScoredDocument> kept = raw.stream().filter(r -> r.score() >= threshold).toList();
        return tableScoped && kept.size() < 3 ? raw : kept;
    }

    /** "la table X" / "table X" cité dans une réponse (X en majuscules), voir {@link #inheritPreviousTable}. */
    private static final Pattern TABLE_MENTION_PATTERN =
            Pattern.compile("(?iu)\\btable\\s+[«\"]?([A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)*)");

    /**
     * Question de suivi courte ("et il est obligatoire ?", "et dans quelle table ?") sans table nommée : elle reste
     * sur la table de la réponse précédente de la conversation, au lieu de chercher un nom de champ commun
     * (CURRENCY, KEY...) dans tout le module et de ramener des tables sans rapport. Ne change rien si une table
     * est déjà désignée, s'il n'y a pas d'historique, ou si la question est longue (nouveau sujet probable).
     */
    private String inheritPreviousTable(String current, String question, List<ChatHistory> history, String module) {
        if (current != null && !current.isBlank()) return current;
        if (history == null || history.isEmpty() || module == null || module.isBlank() || question == null
                || question.trim().split("\\s+").length > 9) return current;
        List<TableT24> tables = tableRepository.findByModule_NomModuleIgnoreCase(module);
        // Les 4 derniers messages, du plus récent au plus ancien : "la table X" dans une réponse, ou un mot de
        // la question/réponse qui est exactement le nom d'une table du module (ex. "currency dans account").
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - 4); i--) {
            ChatHistory h = history.get(i);
            if (h.getContent() == null) continue;
            if ("reponse".equalsIgnoreCase(h.getMessageType())) {
                Matcher m = TABLE_MENTION_PATTERN.matcher(h.getContent());
                while (m.find()) {
                    for (TableT24 t : tables) {
                        if (t.getNomTable().equalsIgnoreCase(m.group(1))) return t.getNomTable();
                    }
                }
            }
            Matcher words = Pattern.compile("[A-Za-z][A-Za-z0-9.]{4,}").matcher(h.getContent());
            while (words.find()) {
                for (TableT24 t : tables) {
                    if (t.getNomTable().equalsIgnoreCase(words.group())) return t.getNomTable();
                }
            }
        }
        return current;
    }

    private static final String OUT_OF_DOMAIN_MESSAGE ="Je suis spécialisé dans la documentation technique "
            + "Temenos T24 : je n'ai pas accès aux données personnelles et je ne peux pas répondre aux questions "
            + "hors de ce domaine. Je peux en revanche vous aider à consulter les modules, tables et champs "
            + "documentés.";

    private record TableOverview(String description, String module) {}

    private static List<SourceFieldDto> buildTableOnlySourceFields(List<RetrieverService.ScoredDocument> results) {
        if (results == null || results.isEmpty()) return List.of();
        Map<String, TableOverview> overviewByTable = new LinkedHashMap<>();
        for (RetrieverService.ScoredDocument r : results) {
            Document doc = r.document();
            if (Document.MODULE_SUMMARY_COLUMN_MARKER.equals(doc.getColumnName())) continue;
            overviewByTable.putIfAbsent(doc.getTableName(), new TableOverview(null, doc.getModule()));
            if (Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(doc.getColumnName())) {
                overviewByTable.put(doc.getTableName(), new TableOverview(extractDescription(doc.getContent()), doc.getModule()));
            }
        }
        return overviewByTable.entrySet().stream()
                .map(e -> new SourceFieldDto(e.getKey(), null, null, e.getValue().description(), null,
                        e.getValue().module(), xmlFileNameFor(e.getKey())))
                .toList();
    }

    /** Nom du fichier HelpText T24 source, déduit du nom de table exact selon la même convention que l'import
     *  réel (voir T24ImportService#importFile : un fichier "NOM.TABLE.xml" produit toujours la table "NOM.TABLE",
     *  donc l'inverse est fiable) — jamais un chemin disque inventé, seulement le nom de fichier reconstruit. */
    private static String xmlFileNameFor(String tableName) {
        return tableName == null || tableName.isBlank() ? null : tableName + ".xml";
    }

    /** Même mapping code -> libellé que l'ancien badge frontend (retiré au profit de la carte "Voir la source"). */
    private static String mandatoryLabelFor(ColumnResultDto c) {
        return switch (c.inputType()) {
            case "MAN" -> "Obligatoire";
            case "NOINP" -> "Auto-calculé";
            case "UNSPEC" -> "Non précisé";
            default -> "Optionnel";
        };
    }

    /** Voir {@link #VALIDATION_RULE_PATTERN} : {@code null} si la Description ne contient aucune clause de
     *  validation explicite (cas fréquent — ne pas inventer une règle qui n'est pas dans le HelpText). */
    private static String extractValidationRule(String description) {
        if (description == null || description.isBlank()) return null;
        Matcher m = VALIDATION_RULE_PATTERN.matcher(description);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Extrait la valeur d'un champ du texte généré par DocumentServiceImpl.buildChunkText (une ligne "Label : valeur" par champ). */
    private static String extractField(String content, String label) {
        int start = content.indexOf(label);
        if (start < 0) return "";
        start += label.length();
        int end = content.indexOf('\n', start);
        return (end < 0 ? content.substring(start) : content.substring(start, end)).trim();
    }

    /** Textes génériques du HelpText T24 (ou de son extraction) quand aucune vraie description n'existe pour ce champ. */
    private static final java.util.Set<String> NO_HELP_TEXT_PLACEHOLDERS = java.util.Set.of(
            "help text for this field is unavailable. please refer to the t24 user guides for further information.",
            "description non disponible.");

    /** Idem, mais pour "Description : " qui est toujours le dernier champ et peut contenir plusieurs lignes (un paragraphe par ligne).
     *  Visibilité publique : réutilisée par StartupReindexRunner pour la traduction française en tâche de fond. */
    public static String extractDescription(String content) {
        String label = "Description : ";
        int start = content.indexOf(label);
        if (start < 0) return "";
        String description = content.substring(start + label.length()).trim();
        return NO_HELP_TEXT_PLACEHOLDERS.contains(description.trim().toLowerCase()) ? "" : description;
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<?> reindex() {
        importService.importAll();
        return ResponseEntity.ok(Map.of(
                "tablesIndexed", tableRepository.count(),
                "modules", moduleNames()));
    }


    /** Repeuple le microservice FAISS à partir des embeddings déjà stockés en base (aucun recalcul d'embedding). */
    @PostMapping("/faiss-backfill")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<?> faissBackfill() {
        int pushed = documentService.backfillFaiss();
        return ResponseEntity.ok(Map.of("vectorsPushed", pushed));
    }

    /**
     * Nettoyage ponctuel : retire de l'index FAISS global les vecteurs des modules hors du périmètre restreint
     * ({@link ActiveModules}), déjà indexés avant cette restriction. Les nouvelles indexations respectent
     * automatiquement le périmètre (voir DocumentServiceImpl), et ce nettoyage tourne aussi seul au démarrage
     * (StartupReindexRunner) : cet endpoint sert à le déclencher immédiatement sans attendre un redémarrage.
     */
    @PostMapping("/faiss-restrict-global")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<?> restrictGlobalIndex() {
        int removed = documentService.restrictGlobalIndexToActiveModules();
        return ResponseEntity.ok(Map.of("vectorsRemovedFromGlobalIndex", removed));
    }

    @GetMapping("/modules")
    public ResponseEntity<List<ModuleSummaryDto>> modules() {
        Map<String, Long> tableCounts = tableRepository.countTablesByModule().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));
        return ResponseEntity.ok(moduleRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(ModuleT24::getNomModule))
                .map(m -> new ModuleSummaryDto(m.getNomModule(), m.getLabel(),
                        tableCounts.getOrDefault(m.getNomModule(), 0L).intValue()))
                .toList());
    }

    /**
     * Libellés de référence pour les modules T24 les plus couramment questionnés en langage naturel : prime sur
     * le libellé auto-extrait de portal-help-menu.xml (parfois absent ou imprécis pour ces modules-clés).
     */
    private static final Map<String, String> CORE_MODULE_LABELS = Map.of(
            "AC", "Account",
            "LD", "Lending",
            "FT", "Funds Transfer",
            "TT", "Teller",
            "FX", "Foreign Exchange",
            "LC", "Letters of Credit",
            "EB", "Electronic Banking"
    );

    /**
     * Résout un texte libre ("account", "les prêts"...) vers les modules T24 dont le libellé est le plus proche,
     * via le LLM local. Le chat est volontairement restreint aux modules {@link ActiveModules#CODES} : les autres
     * modules restent en base (déjà importés) mais ne sont jamais proposés ici, quelle que soit la formulation.
     */
    @GetMapping("/modules/resolve")
    public ResponseEntity<List<ModuleMatchDto>> resolveModule(@RequestParam String q) {
        List<ModuleT24> activeModules = moduleRepository.findAll().stream()
                .filter(m -> ActiveModules.CODE_SET.contains(m.getNomModule().toUpperCase()))
                .toList();
        if (activeModules.isEmpty()) return ResponseEntity.ok(List.of());

        List<AnswerService.ModuleOption> options = activeModules.stream()
                .map(m -> new AnswerService.ModuleOption(m.getNomModule(),
                        CORE_MODULE_LABELS.getOrDefault(m.getNomModule().toUpperCase(), m.getLabel())))
                .toList();

        String matchedCode = answerService.resolveModule(q, options);
        if (matchedCode == null) return ResponseEntity.ok(List.of());

        return activeModules.stream()
                .filter(m -> m.getNomModule().equalsIgnoreCase(matchedCode))
                .findFirst()
                .map(m -> ResponseEntity.ok(List.of(new ModuleMatchDto(m.getNomModule(),
                        CORE_MODULE_LABELS.getOrDefault(m.getNomModule().toUpperCase(), m.getLabel()), 1.0))))
                .orElse(ResponseEntity.ok(List.of()));
    }

    /**
     * Persiste un échange du dialogue guidé (choix de module/table) sans passer par la recherche/LLM,
     * pour que l'historique de conversation restitue fidèlement tout le fil (pas seulement les Q/R finales).
     */
    @PostMapping("/step")
    public ResponseEntity<ChatResponseDto> step(@RequestBody ChatStepDto request, Authentication auth) {
        Chat chat = resolveChat(request.conversationId(), request.userMessage(), auth.getName());
        saveMessage(chat, "question", request.userMessage());
        saveMessage(chat, "reponse", request.botMessage());
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);
        return ResponseEntity.ok(new ChatResponseDto(request.botMessage(), List.of(), "OK", 1.0, LocalDateTime.now(),
                chat.getId(), List.of(), List.of(), null));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health(Authentication auth) {
        // Le superAdmin voit le total tous utilisateurs confondus ; les autres rôles ne voient que leurs propres questions.
        long questionsAnswered = isSuperAdmin(auth)
                ? chatHistoryRepository.countByMessageTypeIgnoreCase("question")
                : chatHistoryRepository.countByChat_UsernameIgnoreCaseAndMessageTypeIgnoreCase(auth.getName(), "question");
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "tablesIndexed", tableRepository.count(),
                "modulesIndexed", moduleRepository.count(),
                "questionsAnswered", questionsAnswered,
                "tableDescriptionsRemainingToTranslate",
                tableRepository.findIdsNeedingFrenchTranslation(CORE_MODULE_LABELS.keySet().stream().toList()).size()));
    }

    @GetMapping("/tables")
    public ResponseEntity<List<TableSummaryDto>> tables() {
        return ResponseEntity.ok(tableRepository.findAll().stream()
                .map(ChatController::toSummary)
                .toList());
    }

    /**
     * Renvoie immédiatement, sans jamais attendre une traduction : la traduction française des descriptions de
     * table tourne en une seule tâche de fond globale au démarrage du backend ({@code StartupReindexRunner}),
     * pas module par module à la demande — évite d'avoir à ouvrir chaque module pour déclencher sa traduction.
     */
    @GetMapping("/tables/module/{nomModule}")
    public ResponseEntity<List<TableSummaryDto>> tablesByModule(@PathVariable String nomModule) {
        return ResponseEntity.ok(tableRepository.findByModule_NomModuleIgnoreCase(nomModule).stream()
                .map(ChatController::toSummary)
                .toList());
    }

    @GetMapping("/tables/module/{nomModule}/columns")
    public ResponseEntity<List<TableColumnsDto>> tablesWithColumns(@PathVariable String nomModule) {
        return ResponseEntity.ok(tableRepository.findByModule_NomModuleIgnoreCase(nomModule).stream()
                .map(ChatController::toColumnsDto)
                .toList());
    }

    private static TableColumnsDto toColumnsDto(TableT24 t) {
        try {
            return new TableColumnsDto(t.getNomTable(), extractColumns(t.getData()));
        } catch (Exception e) {
            log.warn("Impossible d'extraire les colonnes de la table {}", t.getNomTable(), e);
            return new TableColumnsDto(t.getNomTable(), List.of());
        }
    }

    private static List<String> extractColumns(String xml) {
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
            NodeList fields = builder.parse(new InputSource(new StringReader(xml)))
                    .getElementsByTagName("field");
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < fields.getLength(); i++) {
                String name = fields.item(i).getTextContent();
                if (name != null && !name.isBlank()) columns.add(name.trim());
            }
            return columns;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> moduleNames() {
        return moduleRepository.findAll().stream()
                .map(ModuleT24::getNomModule)
                .sorted()
                .toList();
    }

    private static TableSummaryDto toSummary(TableT24 t) {
        String description = t.getDescriptionFr() != null && !t.getDescriptionFr().isBlank()
                ? t.getDescriptionFr()
                : (t.getDescription() != null ? t.getDescription() : "");
        return new TableSummaryDto(t.getNomTable(), t.getModule().getNomModule(), description, 0);
    }

    /**
     * Colonne et sa description, pour la page de détail d'une table T24 (liste "Colonne / Description"). Lecture
     * base uniquement, aucun appel LLM synchrone : la description française vient du cache rempli en tâche de
     * fond (voir StartupReindexRunner) si déjà traduite, sinon le texte anglais brut est affiché immédiatement
     * (la traduction viendra automatiquement dès que la tâche de fond l'aura traitée, sans jamais bloquer l'affichage).
     */
    @GetMapping("/tables/{tableName}/columns")
    public ResponseEntity<List<ColumnDescriptionDto>> tableColumnDescriptions(@PathVariable String tableName) {
        return ResponseEntity.ok(documentRepository.findByTableNameIgnoreCase(tableName).stream()
                .filter(d -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                .map(d -> {
                    String fr = d.getDescriptionFr();
                    String description = (fr != null && !fr.isBlank()) ? fr : extractDescription(d.getContent());
                    return new ColumnDescriptionDto(d.getColumnName(), description);
                })
                .toList());
    }
}
