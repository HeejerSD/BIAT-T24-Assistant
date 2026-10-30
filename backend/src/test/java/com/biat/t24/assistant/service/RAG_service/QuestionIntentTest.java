package com.biat.t24.assistant.service.RAG_service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionIntentTest {

    @Test
    void casualCQuoiPhrasingIsModuleGeneral() {
        // Bug trouve en pratique : question de suivi "c quoi ce module" apres une presentation de module, non
        // reconnue comme MODULE_GENERAL (le motif n'acceptait que "qu'est-ce que"), tombait donc sur le repli
        // connaissances generales au lieu de rester sur la documentation.
        assertEquals(QuestionIntent.MODULE_GENERAL, QuestionIntent.classify("c quoi ce module", true));
        assertEquals(QuestionIntent.MODULE_GENERAL, QuestionIntent.classify("c'est quoi ce module ?", false));
    }

    @Test
    void generalModuleQuestionIsModuleGeneral() {
        assertEquals(QuestionIntent.MODULE_GENERAL, QuestionIntent.classify(
                "Quels types d'informations peut-on retrouver dans la documentation du module LD ?", false));
        assertEquals(QuestionIntent.MODULE_GENERAL, QuestionIntent.classify("Présente-moi le module TT", false));
        assertEquals(QuestionIntent.MODULE_GENERAL, QuestionIntent.classify("À quoi sert le module AC ?", false));
    }

    @Test
    void followUpsRequireHistory() {
        assertEquals(QuestionIntent.FOLLOWUP_EXAMPLE, QuestionIntent.classify("Peux-tu me donner un exemple concret ?", true));
        assertEquals(QuestionIntent.FOLLOWUP_MORE, QuestionIntent.classify("Explique davantage.", true));
        assertEquals(QuestionIntent.FOLLOWUP_VALUES, QuestionIntent.classify("Et ses valeurs ?", true));
        assertEquals(QuestionIntent.FOLLOWUP_VALIDATION,
                QuestionIntent.classify("Et quelles sont les règles de validation ?", true));
        // Sans historique, ce n'est pas une question de suivi.
        assertFalse(QuestionIntent.classify("Explique davantage.", false).isFollowUp());
        assertFalse(QuestionIntent.classify("Peux-tu me donner un exemple concret ?", false).isFollowUp());
    }

    @Test
    void longOrIdentifiedQuestionIsNotAFollowUp() {
        assertFalse(QuestionIntent.classify(
                "Donne-moi un exemple concret de l'utilisation du champ INTEREST.RATE dans la table LD.LOANS.AND.DEPOSITS",
                true).isFollowUp());
        assertFalse(QuestionIntent.classify("Quelles sont les règles de validation de LD.LOANS.AND.DEPOSITS ?", true)
                .isFollowUp());
    }

    @Test
    void topicTablesNeedsThemeOrDetailNotJustListing() {
        // Bug rapporté : filtre thématique + demande de détail (rôle, champs principaux) ignorés, réponse = liste
        // brute de toutes les tables du module.
        assertEquals(QuestionIntent.TOPIC_TABLES, QuestionIntent.classify(
                "quelles sont les tables documentées qui contiennent des informations sur les contrats de credit ? "
                        + "Pour chacune, precise son role et les champs principaux.", false));
        assertEquals(QuestionIntent.TOPIC_TABLES, QuestionIntent.classify(
                "quelles tables sont liees aux paiements ?", false));
        // Une table nommee explicitement reste une question OTHER classique sur CETTE table, jamais un theme.
        assertEquals(QuestionIntent.OTHER, QuestionIntent.classify("explique la table account", false));
        // Un identifiant pointe designe un champ/une table précis : jamais une recherche thematique multi-tables.
        assertFalse(QuestionIntent.TOPIC_TABLES == QuestionIntent.classify(
                "quel est le role du champ INTEREST.RATE ?", false));
    }

    @Test
    void propertyAnswerKeepsOnlyTheFirstSentenceWithoutCuttingIdentifiers() {
        String answer = "Selon la documentation T24, le champ SLEEP.LOCK.TIME accepte une valeur entre 1 et 99 secondes. "
                + "N'hésitez pas à me demander autre chose.";
        assertEquals("Selon la documentation T24, le champ SLEEP.LOCK.TIME accepte une valeur entre 1 et 99 secondes.",
                AnswerServiceImpl.firstSentenceOnly(answer));
    }

    @Test
    void headerlessMarkdownTableBecomesBullets() {
        // Observe en pratique : "Points communs" suivi d'un separateur "| --- | --- | --- |" sans ligne d'en-tete,
        // affiche en barres verticales brutes. Un tableau avec en-tete doit rester intact.
        String broken = "Points communs :\n\n| --- | --- | --- |\n| Contraintes de validation | Règles A | Règles B |";
        String fixed = AnswerServiceImpl.fixHeaderlessTables(broken);
        assertFalse(fixed.contains("| ---"));
        assertTrue(fixed.contains("- Contraintes de validation : Règles A ; Règles B"));
        String withHeader = "| Élément | A |\n| --- | --- |\n| Rôle | x |";
        assertEquals(withHeader, AnswerServiceImpl.fixHeaderlessTables(withHeader));
    }

    @Test
    void unsupportedNotionsAreDroppedFromModuleIntro() {
        String source = "The deal is recorded off balance sheet as contingent entries.";
        String intro = "Ce module gère les deals hors bilan. Il suit aussi les amendes et les contrats non consolidés.";
        assertEquals("Ce module gère les deals hors bilan.", AnswerServiceImpl.dropUnsupportedSentences(intro, source));
    }

    @Test
    void summaryParagraphIsRemovedFromModuleIntro() {
        String intro = "Le module MD gère des deals hors bilan. En résumé, ce module gère les deals.";
        assertEquals("Le module MD gère des deals hors bilan.", AnswerServiceImpl.stripSummaryParagraph(intro));
    }

    @Test
    void metaNarrationLeakIsRemoved() {
        // Observe en pratique : le petit modele local narre parfois sa propre demarche interne au lieu de
        // repondre ("Je vais repondre a votre question en fonction des regles que nous avons etablies.").
        String answer = "Je vais répondre à votre question en fonction des règles que nous avons établies.\n\n"
                + "Le module MD gère les deals hors bilan.";
        String cleaned = AnswerServiceImpl.stripGenericFillerSentences(answer);
        assertFalse(cleaned.toLowerCase().contains("règles que nous avons"));
        assertTrue(cleaned.contains("Le module MD gère les deals hors bilan."));
    }

    @Test
    void closingInvitationIsRemoved() {
        String answer = "Le champ SLEEP.LOCK.TIME indique le temps d'attente avant de réessayer le lock.\n\n"
                + "N'hésitez pas à me demander si vous voulez d'autres informations.";
        assertEquals("Le champ SLEEP.LOCK.TIME indique le temps d'attente avant de réessayer le lock.",
                AnswerServiceImpl.stripClosingInvitation(answer));
    }

    @Test
    void priorQuestionPreambleIsRemovedWhereverItAppears() {
        // Observe en pratique : "Pour répondre à votre question précédente : c'est aux valeurs acceptées…" écrit en
        // milieu de réponse malgré la consigne contraire ; la reprise doit disparaître, le reste doit rester.
        String answer = "Selon la documentation T24, le champ SLEEP.LOCK.TIME permet d'indiquer un temps. "
                + "Pour répondre à votre question précédente : c'est aux valeurs acceptées pour ce champ SLEEP.LOCK.TIME.\n\n"
                + "Il accepte une valeur entre 1 et 99 secondes.";
        String out = AnswerServiceImpl.stripPriorQuestionPreamble(answer);
        assertFalse(out.toLowerCase().contains("question précédente"));
        assertTrue(out.contains("1 et 99 secondes"));
    }

    @Test
    void optionalFieldMidDescriptionIsNotMandatory() {
        // Bug reel : "Optional field. Valid values are: YES/NO" (HVT.NO.LOCK) classe "NON SPECIFIE" car "Optional"
        // n'est pas en tete de description, alors que la doc dit clairement que le champ n'est pas obligatoire.
        assertEquals("NON", MandatoryClassifier.classify(
                "If this field is set to YES and DO.CREDIT.CHECK is set to YES. Validation Rules: Optional field. Valid values are: YES/NO"));
        assertEquals("OUI", MandatoryClassifier.classify("Validation Rules: Mandatory field"));
    }

    @Test
    void responsibleForPhrasingIsTopicTables() {
        // Bug trouve en pratique (module MD) : "quel table est responsable de balances ?" ne matchait aucun motif
        // thematique et tombait sur le repli connaissances generales au lieu de chercher MD.BALANCES dans la doc.
        assertEquals(QuestionIntent.TOPIC_TABLES, QuestionIntent.classify(
                "quel table est responsable de balances ?", false));
        assertEquals(QuestionIntent.TOPIC_TABLES, QuestionIntent.classify(
                "quelle table est en charge de la devise ?", false));
    }

    @Test
    void topicTablesMatchesWithoutTheWordQui() {
        // Bug trouve en pratique (module AC) : "quelles tables de ce module gerent la production de releves de
        // compte ?" omet "qui" ("tables ... gerent", pas "tables qui gerent") et ratait donc TOPIC_TABLES, tombant
        // a tort sur MODULE_GENERAL (motif large "gere" matchant a l'interieur de "gerent") - la reponse devenait
        // une presentation generique du module entier au lieu de repondre a la question thematique posee.
        assertEquals(QuestionIntent.TOPIC_TABLES, QuestionIntent.classify(
                "quelles tables de ce module gerent la production de releves de compte ?", false));
        assertEquals(QuestionIntent.TOPIC_TABLES, QuestionIntent.classify(
                "quelles tables contiennent des informations sur les paiements ?", false));
        // Question fermee classique (verbe au singulier "gere", pas la forme plurielle "gerent" accordee avec
        // "tables") : ne doit jamais devenir TOPIC_TABLES par effet de bord de cet assouplissement.
        assertFalse(QuestionIntent.TOPIC_TABLES == QuestionIntent.classify(
                "le module gere-t-il les tables de blocage ?", false));
    }

    @Test
    void compareNeedsTwoIdentifiers() {
        assertEquals(QuestionIntent.COMPARE, QuestionIntent.classify(
                "Quelle est la différence entre AC.CHARGE.REQUEST et AC.CHARGE.CODE ?", false));
        assertFalse(QuestionIntent.COMPARE == QuestionIntent.classify("Quelle est la différence entre un prêt et un dépôt ?", false));
    }

    @Test
    void closedQuestionIsSimple() {
        assertEquals(QuestionIntent.SIMPLE, QuestionIntent.classify("Est-ce que le champ CURRENCY est obligatoire ?", false));
    }

    @Test
    void standaloneFieldQuestionIsOther() {
        assertEquals(QuestionIntent.OTHER, QuestionIntent.classify("À quoi sert le champ INTEREST.RATE ?", false));
    }

    @Test
    void invalidInputIsOther() {
        assertEquals(QuestionIntent.OTHER, QuestionIntent.classify("", true));
        assertEquals(QuestionIntent.OTHER, QuestionIntent.classify(null, true));
    }

    @Test
    void repetitionLoopsAreRemoved() {
        String looped = "Le champ sert à saisir la date.\n"
                + "- Une date valide est obligatoire pour chaque type.\n"
                + "- Une date valide est obligatoire pour chaque type.\n"
                + "Ce code de charge est défini sur un montant de 1. Ce code de charge est défini sur un montant de 2. "
                + "Ce code de charge est défini sur un montant de 3. Ce code de charge est défini sur un montant de 4.";
        String cleaned = AnswerServiceImpl.removeRepetitions(looped);
        assertEquals(1, cleaned.split("Une date valide est obligatoire", -1).length - 1);
        assertEquals(2, cleaned.split("Ce code de charge est défini", -1).length - 1);
    }

    @Test
    void truncatedTrailingSentenceIsDroppedButListsAreKept() {
        assertEquals("Première phrase complète.",
                AnswerServiceImpl.removeRepetitions("Première phrase complète. Deuxième phrase tronquée au milieu de"));
        String list = "Intro.\n- A : première description\n- B : seconde description sans point";
        assertEquals(list, AnswerServiceImpl.removeRepetitions(list));
    }

    @Test
    void implausibleListLengthHasAFloorForRichSingleFieldAnswers() {
        // Regression trouvee en testant le correctif TOPIC_TABLES : une reponse FIELD (1 seul document de contexte,
        // le champ exact) detaillant plusieurs proprietes documentees du MEME champ en plusieurs puces (comme le
        // demande FIELD_REMINDER) etait a tort rejetee par ce controle (seuil contextSize+2 = 3), la reponse
        // retombant alors sur le dump anglais brut a la place d'une vraie synthese.
        String richSingleFieldAnswer = "Le champ CURRENCY sert a identifier la devise du compte.\n\n"
                + "**Proprietes documentees**\n"
                + "- Type : alphanumerique\n"
                + "- Obligatoire : oui\n"
                + "- Format : 3 caracteres SSS\n"
                + "- Longueur : 3\n"
                + "- Valeurs autorisees : codes existants sur la table CURRENCY\n"
                + "- Valeur par defaut : aucune";
        assertFalse(AnswerServiceImpl.hasImplausibleListLength(richSingleFieldAnswer, 1));
        // Toujours detecte au-dela du plancher (ex. bug d'origine : ~54 "tables" enumerees a partir d'1 document).
        String tooMany = "- item1\n- item2\n- item3\n- item4\n- item5\n- item6\n- item7\n- item8\n- item9\n";
        assertTrue(AnswerServiceImpl.hasImplausibleListLength(tooMany, 1));
    }

    @Test
    void contextTruncationKeepsValidationRules() {
        String longText = "Description : " + "x".repeat(4000) + " Validation Rules: Cannot be greater than 100.";
        String truncated = AnswerServiceImpl.truncateForContext(longText);
        assertTrue(truncated.length() <= 2500 + 20);
        assertTrue(truncated.contains("Cannot be greater than 100"));
        assertEquals("court", AnswerServiceImpl.truncateForContext("court"));
    }

    @Test
    void midSentenceEnglishLeakIsDetected() {
        // Bug rapporte par l'utilisateur : une seule occurrence de "this" (mot ambigu-fort, jamais legitime en
        // francais) ne declenchait pas looksLikeEnglish, car ENGLISH_PROSE_MIN_MATCHES exigeait 2 occurrences.
        assertTrue(AnswerServiceImpl.looksLikeEnglish("This Process Workflow aide l'utilisateur a gerer ses taches."));
        assertTrue(AnswerServiceImpl.looksLikeEnglish("These regles s'appliquent a tous les comptes."));
        // Un mot ambigu isole (emprunt courant en francais technique) ne doit toujours pas suffire seul.
        assertFalse(AnswerServiceImpl.looksLikeEnglish("Le workflow d'approbation est defini dans cette table."));
        assertFalse(AnswerServiceImpl.looksLikeEnglish("Selon la documentation T24, ce champ identifie le compte."));
        // Bug trouve en re-testant "Presente-moi le module EB" (puis LC) : le libelle officiel anglais d'un
        // module ("Letters of Credit") cite deux fois (titre + intro) contenait deux occurrences de "of", a tort
        // rejete comme fuite anglaise alors qu'un nom officiel de module doit rester en anglais.
        assertFalse(AnswerServiceImpl.looksLikeEnglish(
                "**Module Letters of Credit**\n\nLe module Letters of Credit gere les lettres de credit."));
    }

    @Test
    void deLeContractionsAreFixed() {
        // Bug trouve en re-testant "Presente-moi le module EB" : "de le champ" au lieu de "du champ".
        assertEquals("la description du champ SYSTEM.ID",
                AnswerServiceImpl.fixFrenchElisions("la description de le champ SYSTEM.ID"));
        assertEquals("les regles des tables associees",
                AnswerServiceImpl.fixFrenchElisions("les regles de les tables associees"));
        // "a" nu (verbe avoir) ne doit jamais etre touche.
        assertEquals("Il a le droit de consulter.", AnswerServiceImpl.fixFrenchElisions("Il a le droit de consulter."));
    }

    @Test
    void frenchElisionsAreFixed() {
        assertEquals("d'effectuer des operations bancaires.",
                AnswerServiceImpl.fixFrenchElisions("de effectuer des operations bancaires."));
        assertEquals("Il permet a l'utilisateur d'acceder a l'application.",
                AnswerServiceImpl.fixFrenchElisions("Il permet a le utilisateur de acceder a la application."));
        assertEquals("Qu'il le fasse.", AnswerServiceImpl.fixFrenchElisions("Que il le fasse."));
        // Rien a corriger : le texte reste identique.
        assertEquals("Le champ CURRENCY est obligatoire.",
                AnswerServiceImpl.fixFrenchElisions("Le champ CURRENCY est obligatoire."));
    }

    @Test
    void trailingHeadingWithNoNewlineAfterIsAlsoStrippedIfEmpty() {
        // Bug trouve en testant la deduplication de tables : un dernier titre sans AUCUN saut de ligne apres lui
        // (donc sans corps du tout) n'etait jamais reconnu comme vide (indexOf('\n') = -1 capturait a tort le
        // titre lui-meme comme "corps").
        String endingOnEmptyHeading = "## Tables et Donnees\n- AC.EVENT : role.\n\n## Configuration et Parametrage";
        String cleaned = AnswerServiceImpl.stripEmptyModuleSections(endingOnEmptyHeading);
        assertFalse(cleaned.contains("Configuration et Parametrage"));
        assertTrue(cleaned.contains("AC.EVENT"));
    }

    @Test
    void emptyModuleSectionsAreStripped() {
        // Bug trouve en verifiant le correctif "Presente-moi le module EB" : une rubrique gardee malgre la
        // consigne de l'omettre quand aucune table du contexte n'en releve, avec pour seul contenu une phrase
        // constatant l'absence (jamais une vraie puce).
        String withEmptySection = "Selon la documentation T24, le module AC sert a gerer les comptes.\n\n"
                + "## Tables et Donnees\n"
                + "- AC.EVENT : contient des evenements lies aux comptes bancaires.\n\n"
                + "## Configuration et Parametrage\n"
                + "Aucune table n'est specifiquement mentionnee dans le contexte pour ce module.";
        String cleaned = AnswerServiceImpl.stripEmptyModuleSections(withEmptySection);
        assertFalse(cleaned.contains("Configuration et Parametrage"));
        assertTrue(cleaned.contains("AC.EVENT"));

        // Une rubrique avec une vraie puce n'est jamais retiree, meme si elle contient aussi une phrase similaire.
        String withRealBullet = "## Configuration et Parametrage\n"
                + "- AC.PARAM : definit des parametres de gestion des comptes.";
        assertEquals(withRealBullet, AnswerServiceImpl.stripEmptyModuleSections(withRealBullet));
    }

    @Test
    void genericFillerSentencesAreStripped() {
        // Bug trouve en testant "Presente-moi le module EB" : le modele reutilise une formule creuse malgre
        // l'interdiction explicite du reminder.
        String withFiller = "Selon la documentation T24, le module EB gere les operations electroniques. "
                + "Il s'agit d'un acteur incontournable pour la gestion des flux de travail et des processus metier. "
                + "La table EB.SYSTEM.ID confirme ce role.";
        String cleaned = AnswerServiceImpl.stripGenericFillerSentences(withFiller);
        assertFalse(cleaned.toLowerCase().contains("acteur incontournable"));
        assertTrue(cleaned.contains("EB.SYSTEM.ID"));
        assertTrue(cleaned.contains("gere les operations electroniques"));
        // Rien a retirer : le texte reste identique.
        String clean = "Selon la documentation T24, le module EB gere les operations electroniques.";
        assertEquals(clean, AnswerServiceImpl.stripGenericFillerSentences(clean));
    }

    @Test
    void headingsGetABlankLineBefore() {
        // Bug trouve en re-testant "Presente-moi le module EB" : titre colle a la phrase precedente, illisible
        // pour le renderer Markdown du frontend qui exige une ligne vide avant un titre.
        assertEquals("Intro.\n\n## Titre\n- item",
                AnswerServiceImpl.ensureBlankLineBeforeHeadings("Intro. ## Titre\n- item"));
        assertEquals("Intro.\n\n## Titre\n- item",
                AnswerServiceImpl.ensureBlankLineBeforeHeadings("Intro.\n## Titre\n- item"));
        // Deja correctement espace : inchange.
        assertEquals("Intro.\n\n## Titre\n- item",
                AnswerServiceImpl.ensureBlankLineBeforeHeadings("Intro.\n\n## Titre\n- item"));
    }

    private static com.biat.t24.assistant.model.Document overviewDoc(String module, String table, String content) {
        com.biat.t24.assistant.model.Document d = new com.biat.t24.assistant.model.Document();
        d.setModule(module);
        d.setTableName(table);
        d.setColumnName(com.biat.t24.assistant.model.Document.TABLE_OVERVIEW_COLUMN_MARKER);
        d.setContent(content);
        return d;
    }

    @Test
    void moduleIntroSentenceIsBuiltFromRealContextData() {
        // Bug trouve en re-testant "Presente-moi le module EB" a plusieurs reprises : l'introduction redigee par
        // le modele restait creuse ou circulaire ("gere les operations d'Electronic Banking"). L'intro est
        // maintenant construite deterministiquement a partir des vraies donnees du contexte, jamais par le LLM.
        com.biat.t24.assistant.model.Document summary = new com.biat.t24.assistant.model.Document();
        summary.setModule("EB");
        summary.setTableName("EB");
        summary.setColumnName(com.biat.t24.assistant.model.Document.MODULE_SUMMARY_COLUMN_MARKER);
        summary.setContent("Module : EB\nLibellé officiel du module : Electronic Banking\n"
                + "Nombre de tables documentées : 250\nLes tables decrites ci-dessous...");
        List<RetrieverService.ScoredDocument> context = List.of(
                new RetrieverService.ScoredDocument(summary, 1.0),
                new RetrieverService.ScoredDocument(overviewDoc("EB", "EB.SYSTEM.ID", "..."), 0.9),
                new RetrieverService.ScoredDocument(overviewDoc("EB", "EB.PHANTOM", "..."), 0.8));
        String intro = AnswerServiceImpl.moduleIntroSentence(context);
        assertTrue(intro.contains("EB"));
        assertTrue(intro.contains("Electronic Banking"));
        assertTrue(intro.contains("250"));
        assertTrue(intro.contains("EB.SYSTEM.ID"));
        assertTrue(intro.contains("EB.PHANTOM"));
        // Aucune formule de synthese du module dans le contexte : chaine vide plutot qu'inventer.
        assertEquals("", AnswerServiceImpl.moduleIntroSentence(List.of(
                new RetrieverService.ScoredDocument(overviewDoc("EB", "EB.SYSTEM.ID", "..."), 0.9))));
    }

    @Test
    void moduleRubricsAreIsolatedFromStrayIntroOrConclusion() {
        // Le LLM ignore parfois la consigne de ne pas rediger sa propre intro/conclusion malgre MODULE_REMINDER :
        // seul le bloc structure (titres + puces) doit etre conserve.
        String withStrayText = "Le module EB gere les operations electroniques.\n\n"
                + "## Tables et Donnees\n- EB.SYSTEM.ID : role.\n\n"
                + "## Processus et Flux de travail\n- EB.PHANTOM : role.\n\n"
                + "Ce module est essentiellement lie a l'interface electronique avec les clients.";
        String isolated = AnswerServiceImpl.isolateModuleRubrics(withStrayText);
        assertFalse(isolated.contains("gere les operations electroniques"));
        assertFalse(isolated.contains("essentiellement lie"));
        assertTrue(isolated.contains("## Tables et Donnees"));
        assertTrue(isolated.contains("EB.PHANTOM"));
        assertTrue(isolated.startsWith("##"));
    }

    @Test
    void tablesAreNeverRepeatedAcrossRubrics() {
        // Bug trouve en re-testant AC/LD : une meme table (ex. AC.STMT.HANDOFF) citee sous 3 rubriques a la fois.
        String withDuplicates = "## Tables et Donnees\n- AC.STMT.HANDOFF : role A.\n- AC.NAU.ENTRY : role B.\n\n"
                + "## Processus et Flux de travail\n- AC.STMT.HANDOFF : role A redondant.\n\n"
                + "## Configuration et Parametrage\n- AC.STMT.HANDOFF : encore redondant.";
        String dedup = AnswerServiceImpl.dedupTablesAcrossRubrics(withDuplicates);
        assertEquals(1, dedup.split("AC\\.STMT\\.HANDOFF", -1).length - 1);
        assertTrue(dedup.contains("AC.NAU.ENTRY"));
        // Les deux rubriques devenues vides apres deduplication (leur unique table etait un doublon) sont retirees.
        assertFalse(dedup.contains("Configuration et Parametrage"));
        assertFalse(dedup.contains("Processus et Flux de travail"));
        assertTrue(dedup.contains("Tables et Donnees"));
    }

    @Test
    void nearMissIdentifiersAreAutoCorrectedBeforeValidation() {
        // Bug trouve en re-testant "Presente-moi le module AC" : "AC.PRE.CLOSURE.DETS" (segment abrege) pour le
        // vrai "AC.PRE.CLOSURE.DETAILS" epuisait les 2 tentatives (rejet strict), alors qu'une reponse par
        // ailleurs fidele et bien structuree n'avait besoin que d'une simple correction orthographique.
        List<RetrieverService.ScoredDocument> context = List.of(
                new RetrieverService.ScoredDocument(overviewDoc("AC", "AC.PRE.CLOSURE.DETAILS", "..."), 0.9));
        String answer = "- AC.PRE.CLOSURE.DETS : sert de modele de verification avant la fermeture d'un compte.";
        String corrected = AnswerServiceImpl.autoCorrectNearMissIdentifiers(answer, context);
        assertTrue(corrected.contains("AC.PRE.CLOSURE.DETAILS"));
        assertFalse(corrected.contains("AC.PRE.CLOSURE.DETS"));
        // Le meme mecanisme de prefixe double que dans repairIdentifiers (ChatController).
        List<RetrieverService.ScoredDocument> contextTT = List.of(
                new RetrieverService.ScoredDocument(overviewDoc("TT", "TT.TELLER.HP.VERSIONS", "..."), 0.9));
        assertEquals("- TT.TELLER.HP.VERSIONS : role.",
                AnswerServiceImpl.autoCorrectNearMissIdentifiers("- TT.TT.TELLER.HP.VERSIONS : role.", contextTT));
    }

    @Test
    void llmModuleIntroIsExtractedWithoutDuplicateOpenerOrTableCount() {
        // Bug rapporte : l'introduction ne doit plus commencer par un decompte de tables ("regroupe N tables"),
        // ni dupliquer la formule de source deja ajoutee separement par moduleAnchorFact.
        String cleaned = "Selon la documentation T24, le module EB regroupe 395 tables documentees. "
                + "Il gere les identifiants systeme, le mapping de donnees entre tables et le controle des jobs "
                + "techniques.\n\n## Tables et Donnees\n- EB.SYSTEM.ID : role.";
        String intro = AnswerServiceImpl.extractLlmModuleIntro(cleaned);
        assertFalse(intro.toLowerCase().contains("regroupe 395 tables"));
        assertFalse(intro.toLowerCase().contains("selon la documentation"));
        assertTrue(intro.contains("identifiants systeme"));

        // Aucune introduction laissee par le LLM (tout de suite une rubrique) : chaine vide, jamais une erreur.
        assertEquals("", AnswerServiceImpl.extractLlmModuleIntro("## Tables et Donnees\n- EB.SYSTEM.ID : role."));
    }

    @Test
    void selfInventedEnglishModuleNameIsStrippedFromIntro() {
        // Bug trouve en re-testant "Presente-moi le module EB" : le LLM reinvente un libelle anglais ("Electronic
        // Banking") juste apres que moduleAnchorFact ait pourtant donne le VRAI libelle ("System Core").
        String cleaned = "Le module Electronic Banking (EB) est un systeme core qui gere les identifiants "
                + "systeme et le controle des jobs techniques.\n\n## Tables et Donnees\n- EB.SYSTEM.ID : role.";
        String intro = AnswerServiceImpl.extractLlmModuleIntro(cleaned);
        assertFalse(intro.toLowerCase().contains("electronic banking"));
        assertTrue(intro.contains("systeme core"));
        assertTrue(Character.isUpperCase(intro.charAt(0)));

        // Meme invention, mais sans parentheses ("Le module Electronic Banking gere...") : le verbe reste avec
        // un sujet grammatical ("Il gere...", jamais juste "Gere...").
        String cleaned2 = "Le module Electronic Banking gere les identifiants systeme et le controle des jobs "
                + "techniques.\n\n## Tables et Donnees\n- EB.SYSTEM.ID : role.";
        String intro2 = AnswerServiceImpl.extractLlmModuleIntro(cleaned2);
        assertFalse(intro2.toLowerCase().contains("electronic banking"));
        assertTrue(intro2.toLowerCase().startsWith("il gere les identifiants"));

        // Ligne-titre autonome ("Module AC") suivie d'une phrase de re-nommage ("Le module Account gere...") :
        // les deux doivent disparaitre, laissant une phrase avec sujet grammatical correct.
        String cleaned3 = "Module AC\nLe module Account gere les comptes bancaires et les operations "
                + "financieres.\n\n## Tables et Donnees\n- AC.EVENT : role.";
        String intro3 = AnswerServiceImpl.extractLlmModuleIntro(cleaned3);
        assertFalse(intro3.toLowerCase().contains("module ac"));
        assertFalse(intro3.toLowerCase().contains("module account"));
        assertTrue(intro3.toLowerCase().startsWith("il gere les comptes"));
    }

    @Test
    void moduleAnchorFactNeverInventsTheLabel() {
        com.biat.t24.assistant.model.Document summary = new com.biat.t24.assistant.model.Document();
        summary.setModule("EB");
        summary.setTableName("EB");
        summary.setColumnName(com.biat.t24.assistant.model.Document.MODULE_SUMMARY_COLUMN_MARKER);
        summary.setContent("Module : EB\nLibellé officiel du module : System Core\n"
                + "Nombre de tables documentées : 395\nLes tables decrites ci-dessous...");
        List<RetrieverService.ScoredDocument> context = List.of(new RetrieverService.ScoredDocument(summary, 1.0));
        String anchor = AnswerServiceImpl.moduleAnchorFact(context);
        assertTrue(anchor.contains("System Core"));
        assertFalse(anchor.toLowerCase().contains("electronic banking"));
        // Jamais de decompte de tables dans le fait de rattachement (contrairement a l'ancien moduleIntroSentence).
        assertFalse(anchor.contains("395"));
    }

    @Test
    void moduleHeadingsAreNormalizedRegardlessOfMarkup() {
        // Bug trouve en re-testant "Presente-moi le module EB" : le modele derive vers "**Titre**" ou du texte
        // brut au lieu de "## Titre", faisant echouer isolateModuleRubrics/extractLlmModuleIntro (aucun "##"
        // trouve) et dupliquant integralement la reponse.
        String bold = "Intro.\n\n**Tables et Données**\n- EB.SYSTEM.ID : role.\n\nProcessus et Flux de travail\n- EB.PHANTOM : role.";
        String normalized = AnswerServiceImpl.normalizeModuleHeadings(bold);
        assertTrue(normalized.contains("## Tables et Données"));
        assertTrue(normalized.contains("## Processus et Flux de travail"));
        assertFalse(normalized.contains("**Tables"));
        // Deja au bon format : inchange.
        String already = "## Tables et Données\n- EB.SYSTEM.ID : role.";
        assertEquals(already, AnswerServiceImpl.normalizeModuleHeadings(already));
    }

    @Test
    void inventedTablesAreFilteredOut() {
        // Bug trouve en re-testant "Presente-moi le module EB" : STMT.ENTRY etc., simples noms de fichiers cites
        // EN PASSANT dans la description d'EB.SYSTEM.ID, promus a tort en "tables" avec description inventee.
        String rubrics = "## Tables et Donnees\n- EB.SYSTEM.ID : role reel.\n\n"
                + "## Utilisateurs et Documentation\n- STMT.ENTRY : fichier des mouvements de compte.\n"
                + "- CATEG.ENTRY : fichier des categories.";
        Set<String> known = Set.of("EB.SYSTEM.ID");
        String filtered = AnswerServiceImpl.keepOnlyKnownTables(rubrics, known);
        assertTrue(filtered.contains("EB.SYSTEM.ID"));
        assertFalse(filtered.contains("STMT.ENTRY"));
        assertFalse(filtered.contains("CATEG.ENTRY"));
        // La rubrique devenue entierement vide est retiree.
        assertFalse(filtered.contains("Utilisateurs et Documentation"));
    }

    @Test
    void inventedFieldsAreFilteredOutOfGeneralAnswers() {
        // Bug trouve en pratique (module AC, question "quelles tables gerent la production de releves de compte
        // ?") : "MVMT.DETAIL" cite comme un champ documente de AC.ALLOCATION.RULE avec son propre role, alors que
        // ce nom n'est JAMAIS un champ de cette table - seulement une VALEUR mentionnee dans la Description d'un
        // AUTRE champ reel (MVMT.TARGET). Meme trou que keepOnlyKnownTables, pour la reponse generale (hors
        // moduleMode) qui enumere des tables ET des champs en puces.
        String answer = "Selon la documentation T24 :\n\n### AC.ALLOCATION.RULE\n"
                + "Role : definit les regles d'allocation comptable.\n\n"
                + "- MVMT.TARGET : cible du mouvement.\n"
                + "- MVMT.DETAIL : detail du mouvement, valeur STMT pour statement.\n"
                + "- EVENT.TYPE : type d'evenement associe.";
        Set<String> known = Set.of("AC.ALLOCATION.RULE", "MVMT.TARGET", "EVENT.TYPE");
        String filtered = AnswerServiceImpl.keepOnlyKnownIdentifiers(answer, known);
        assertTrue(filtered.contains("MVMT.TARGET"));
        assertTrue(filtered.contains("EVENT.TYPE"));
        assertFalse(filtered.contains("MVMT.DETAIL"));
    }

    @Test
    void trailingClosingSentenceGluedToLastBulletIsStripped() {
        // Bug trouve en re-testant "Presente-moi le module AC" : phrase de cloture accolee a la derniere puce
        // SANS ligne vide, donc gardee a tort par l'isolement au niveau paragraphe.
        String withGluedClosing = "## Configuration et Paramétrage\n"
                + "- AC.DEFINE.PAY.RECV.ALLOWED : role reel.\n"
                + "N'hésitez pas à me demander si vous voulez en savoir plus.";
        String isolated = AnswerServiceImpl.isolateModuleRubrics(withGluedClosing);
        assertFalse(isolated.toLowerCase().contains("hésitez"));
        assertTrue(isolated.contains("AC.DEFINE.PAY.RECV.ALLOWED"));
    }

    @Test
    void invertedTurnsAreCutFromAnswer() {
        String answer = "Le champ sert à identifier le prêt.\n\nQuestion : Et pour le montant ?\nRéponse : autre chose";
        assertEquals("Le champ sert à identifier le prêt.", AnswerServiceImpl.cutInventedTurns(answer));
        assertEquals("Texte normal sur deux lignes\nsans tour inventé.",
                AnswerServiceImpl.cutInventedTurns("Texte normal sur deux lignes\nsans tour inventé."));
        assertNull(AnswerServiceImpl.cutInventedTurns(null));
        // Jamais de réponse vide : si tout est "inventé", le texte est conservé tel quel.
        assertEquals("Utilisateur : bonjour", AnswerServiceImpl.cutInventedTurns("Utilisateur : bonjour"));
    }
}
