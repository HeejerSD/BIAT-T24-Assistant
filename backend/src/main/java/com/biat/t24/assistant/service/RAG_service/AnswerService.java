package com.biat.t24.assistant.service.RAG_service;

import java.util.List;
import java.util.function.Consumer;

public interface AnswerService {

    /**
     * Génère une réponse en langage naturel à {@code question}, à partir du contexte {@code context}
     * (chunks déjà retrouvés par {@link RetrieverService}) et de {@code conversationHistory} (derniers échanges
     * du même fil, format "Utilisateur : ...\nAssistant : ...", chaîne vide si aucun historique) pour que le LLM
     * comprenne les questions de suivi ("réponds brièvement", "et pour X ?"). Retourne {@code null} si le LLM est
     * indisponible ou en erreur, à charge de l'appelant de prévoir un repli. {@code module} (peut être {@code null})
     * sert uniquement à valider après coup que le LLM ne cite pas un identifiant hors contexte (voir
     * AnswerServiceImpl#citesOnlyContextIdentifiers) : un identifiant cité avec ce préfixe module (ex. "AC.ACCOUNT"
     * pour la table "ACCOUNT" du module AC) est reconnu comme faisant bien partie du contexte.
     */
    String generate(String question, List<RetrieverService.ScoredDocument> context, String conversationHistory,
                     String module, QuestionIntent intent);

    /**
     * Variante "streaming" de {@link #generate} : au lieu d'attendre la réponse complète, invoque {@code onToken}
     * pour chaque fragment de texte généré par le LLM au fur et à mesure (pour un affichage progressif côté
     * utilisateur). Ne retourne rien : l'appelant assemble lui-même le texte complet en concaténant les fragments
     * reçus. N'appelle jamais {@code onToken} si le LLM est indisponible (log + arrêt silencieux, comme les
     * autres méthodes de ce service) — à charge de l'appelant de détecter qu'aucun fragment n'a été reçu.
     */
    void generateStreaming(String question, List<RetrieverService.ScoredDocument> context, String conversationHistory,
                            Consumer<String> onToken);

    /**
     * Génère une réponse à partir des seules connaissances générales du LLM sur Temenos T24, sans aucun contexte
     * issu de la documentation locale de la banque, mais en tenant compte de {@code conversationHistory} (voir
     * {@link #generate}). Utilisé en repli quand la recherche documentaire ne trouve rien de pertinent. Retourne
     * {@code null} si le LLM est indisponible.
     */
    String generateWithoutContext(String question, String conversationHistory);

    /** Variante "streaming" de {@link #generateWithoutContext}, voir {@link #generateStreaming}. */
    void generateWithoutContextStreaming(String question, String conversationHistory, Consumer<String> onToken);

    /**
     * Traduit un texte technique T24 (HelpText, en anglais dans la documentation source) vers le français, pour
     * l'affichage des pages de détail de table/colonne. Retourne le texte original si le LLM est indisponible
     * ou en erreur (ne bloque jamais l'affichage).
     */
    String translateToFrench(String text);

    /**
     * Traduit un lot de textes en une seule requête LLM (bien plus rapide que {@link #translateToFrench} appelé un
     * par un sur des milliers de tables), en conservant l'ordre. Si la réponse du LLM est mal formée (nombre de
     * lignes incorrect), bascule automatiquement sur une traduction individuelle de ce lot : plus lent pour ce lot
     * précis, mais ne mélange ni ne perd jamais une traduction.
     */
    List<String> translateBatchToFrench(List<String> texts);

    /**
     * Génère, en français, une phrase résumant à quoi sert une table T24 à partir de la description de ses
     * colonnes, utilisée quand la documentation source ne fournit aucune description de table (section overview
     * absente du HelpText). Retourne {@code null} si {@code columnDescriptions} est vide ou si le LLM échoue :
     * à charge de l'appelant de traiter ce cas comme "aucune description disponible", jamais comme une donnée
     * officielle.
     */
    String summarizeTable(String tableName, List<String> columnDescriptions);

    /** Un module T24 candidat proposé au LLM pour la résolution de {@code query} (code + libellé humain, peut être null). */
    record ModuleOption(String code, String label) {}

    /**
     * Demande au LLM de choisir, parmi {@code options}, le module T24 correspondant le mieux à un texte libre
     * ("account", "les prêts"...). La liste des modules T24 étant restreinte (quelques dizaines), il suffit de
     * la fournir en intégralité dans le prompt, sans recherche vectorielle. Retourne le code du module choisi,
     * ou {@code null} si aucun ne correspond ou si le LLM est indisponible.
     */
    String resolveModule(String query, List<ModuleOption> options);

    /**
     * Détecte si le vocabulaire métier de {@code question} correspond, sans ambiguïté, à un AUTRE module que
     * {@code currentModule} (déjà sélectionné dans la conversation) — via les mêmes mots-clés déterministes que
     * {@link #resolveModule} (ex. "crédit"/"prêt"/"lending" -> LD). Sert à repérer le cas où l'utilisateur pose
     * une question hors du périmètre du module actuellement sélectionné (ex. une question sur les crédits alors
     * que le module AC est sélectionné) : la recherche restant confinée au module choisi, elle ne peut jamais
     * suggérer d'elle-même un autre module — sans ce signal, elle finit par accrocher un champ sans rapport dans
     * le mauvais module plutôt que de dire que le sujet ne s'y trouve probablement pas. Retourne {@code null} si
     * aucun mot-clé ne correspond, ou si plusieurs modules différents correspondent à la fois (ambigu, mieux ne
     * rien suggérer qu'une piste possiblement fausse).
     */
    String suggestOtherModule(String question, String currentModule);

    /**
     * Vrai si {@code question} relève du domaine de l'assistant (banque, finance, Temenos T24, leurs données et
     * leur documentation) ; faux pour une question personnelle ou totalement hors domaine (informatique générale,
     * cuisine...). Utilisé uniquement quand la documentation ne renvoie rien de pertinent, pour rediriger plutôt
     * que de répondre. Retourne {@code true} (ne bloque rien) si le LLM est indisponible.
     */
    boolean isInDomain(String question);

    /**
     * Reformule {@code question} en une question autonome et précise pour la recherche documentaire, en résolvant
     * les références implicites à l'historique de conversation ({@code conversationHistory}, même format que
     * {@link #generate}) — ex. "il appartient à quelle table ?" après avoir parlé de MIN.COMM.AMT.LCCY devient
     * "MIN.COMM.AMT.LCCY appartient à quelle table ?". Ne doit jamais changer le sens de la question ni ajouter
     * une information absente de l'historique. Utilisée uniquement pour la RECHERCHE (question posée au LLM pour
     * la réponse finale inchangée) : une reformulation imparfaite ne dégrade donc que la précision de la
     * recherche, jamais le texte de la réponse. Retourne {@code question} inchangée si {@code conversationHistory}
     * est vide/blanc ou si le LLM est indisponible.
     */
    String rewriteQuery(String question, String conversationHistory);
}
