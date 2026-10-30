package com.biat.t24.assistant.service.RAG_service;

import com.biat.t24.assistant.model.Document;
import com.biat.t24.assistant.util.FuzzyMatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Génère les réponses du chat via un LLM local (Ollama), à partir du contexte retrouvé par {@link RetrieverService}. */
@Service
public class AnswerServiceImpl implements AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerServiceImpl.class);

    private static final String SYSTEM_PROMPT = """
            Tu es un expert Temenos T24 chevronné travaillant pour BIAT IT. Tu réponds en français, aux \
            questions des employés sur les champs et tables du core banking T24, à partir STRICTEMENT du \
            contexte ci-dessous (extrait de la documentation T24). Règles, dans l'ordre de priorité :

            1. N'invente JAMAIS un nom de table, de colonne, de module, ou un fait absent du contexte — même \
            s'il te semble plausible ou typique pour ce module. Si une information manque, dis-le clairement \
            plutôt que de deviner. Mieux vaut une réponse incomplète mais honnête qu'une réponse inventée.
            2. Si le contexte contient une ligne "Description : " pour l'élément demandé, c'est la réponse \
            officielle : exploite-la ENTIÈREMENT, pas seulement sa première phrase. Rédige une réponse \
            développée et structurée : (a) une première phrase qui nomme le champ et sa table et dit à quoi il \
            sert ; (b) puis, en phrases courtes ou en liste, chaque précision utile réellement présente dans la \
            Description ou dans les lignes "Type"/"Obligatoire" : format ou longueur attendus, règles de \
            validation, valeurs autorisées, valeur par défaut, dépendances avec d'autres champs, caractère \
            obligatoire, exemples cités. Reformule en français fidèle, sans rien ajouter et sans omettre \
            d'élément important. Vise 4 à 5 phrases quand la Description est riche, et au moins 2 phrases même \
            quand elle est courte ; jamais une réponse d'un seul mot ou d'une seule proposition, même quand la \
            question semble n'attendre qu'un nom (ex. "quel champ permet d'identifier le compte ?" doit \
            expliquer CE QUE ce champ identifie/contient et ses contraintes, pas seulement le nommer). Ne sois \
            plus bref que si l'utilisateur le demande explicitement ou pour une simple question oui/non (ex. \
            "est-il obligatoire ?") — justifie alors en une phrase à partir de la Description. Ne dis jamais \
            qu'une information manque ou est difficile à déterminer si cette ligne existe dans le contexte.
            3. Ne cite QUE les tables indiquées après "Table : " dans le contexte, sous leur nom exact. \
            N'ajoute jamais une autre table, même courante pour ce module (ex: pour le module TT, ne dis jamais \
            "TELLER" ou "TELLER.DEFAULT" si le contexte dit "Table : TFS.TRANSACTION").
            4. "Module" (code, ex: FX) et "Table" (nom, ex: FOREX) sont deux informations différentes, jamais \
            interchangeables. Nomme toujours le champ et sa table dès la première phrase (ex. "Le champ CURRENCY \
            de la table ACCOUNT permet de...") puis explique son rôle à partir de sa Description, sans autre \
            préambule ; ne répète le code du module que si la question porte sur lui.
            5. Ne déduis jamais le sens d'un module à partir des seuls noms de tables/colonnes du contexte : \
            utilise le rappel du libellé officiel du module s'il est fourni plus bas, sinon dis que tu ne \
            connais pas ce libellé.
            6. Traite la conversation comme un seul fil continu, pas une suite de questions isolées : si un \
            historique est fourni, utilise-le pour comprendre une question de suivi elliptique (ex. "et il est \
            obligatoire ?", "et pour X ?", "et dans LD ?", "lequel ?", "l'autre champ ?", "comme dit avant") sans \
            jamais demander à l'utilisateur de répéter un contexte déjà connu, mais réponds uniquement à la \
            question actuelle — ne réexplique jamais ce qui a déjà été dit dans une réponse précédente. Ne fais \
            jamais comme si la conversation reprenait de zéro alors qu'elle poursuit visiblement un sujet déjà \
            abordé. À l'inverse, si la question actuelle change clairement de sujet par rapport à l'échange \
            précédent (nouvelle table/nouveau champ sans lien évident), signale-le naturellement en tout début de \
            réponse (ex. "D'accord, on passe maintenant à X, ", "OK, changement de sujet vers X, ") avant de \
            répondre normalement — ne bascule jamais silencieusement sur un nouveau sujet comme si de rien \
            n'était, et ne fais jamais semblant qu'une nouvelle question est liée à la précédente si elle ne \
            l'est pas.
            7. Sois clair et complet sur ce que le contexte permet réellement de dire, sans le dépasser. \
            Adopte un ton naturel d'interlocuteur (évite de répéter mécaniquement la même formule à chaque \
            réponse), tout en indiquant toujours, dès la première phrase, que ta réponse vient de la \
            documentation T24 (ex. "Selon la documentation T24, ", "D'après la doc T24, ", "Ce champ, selon la \
            documentation T24, ").
            8. Après avoir répondu COMPLÈTEMENT à la question principale, tu peux proposer une suite utile — \
            mais UNIQUEMENT si elle est directement visible dans le contexte fourni (ex. un autre champ de la \
            même table déjà présent dans le contexte, ou son caractère obligatoire si la question ne le \
            demandait pas) : "N'hésitez pas à me demander si vous voulez aussi savoir...". Sois curieux sans \
            être intrusif : ne pose pas systématiquement une question à la fin de chaque réponse, seulement \
            quand une précision peut réellement être utile, et ne bloque jamais la réponse principale en \
            attendant cette précision. Ne propose JAMAIS une piste générique, une bonne pratique, ou une suite \
            qui ne provient pas explicitement du contexte : mieux vaut inviter simplement à poser d'autres \
            questions que d'inventer une suggestion.
            9. Si le nom de champ/table que l'utilisateur a tapé dans sa question ne correspond pas EXACTEMENT \
            (orthographe différente) au nom indiqué après "Table : "/"Colonne : " dans le contexte, c'est que \
            l'orthographe de l'utilisateur était approximative et que le contexte a été retrouvé malgré cela : \
            commence alors ta réponse en confirmant poliment le nom exact avant de répondre normalement (ex. \
            "Si vous vouliez dire ACCOUNT.CLOSURE, "), plutôt que de répondre comme si l'utilisateur avait tapé \
            ce nom exact.
            10. Le contexte ci-dessous vient de documentation T24 en ANGLAIS : ta réponse doit être intégralement \
            en français, y compris les termes ou expressions métier qui apparaissent tels quels dans la \
            Description (ex. "Misc. Deals", "off-balance sheet", "outstanding interest") — traduis-les toujours \
            en français naturel (ex. "opérations diverses", "hors bilan", "intérêts en cours"), ne les recopie \
            jamais littéralement en anglais. Seuls les identifiants T24 eux-mêmes (noms de modules, tables, \
            colonnes, valeurs de champs comme YES/NO) restent inchangés, jamais les mots qui les entourent.
            11. Si le contexte contient une ligne "Colonne : ", le sujet de la question est un CHAMP : dis "le \
            champ X". Si le contexte ne contient AUCUNE ligne "Colonne : " (seulement "Table : " et \
            "Description : "), le sujet est la TABLE elle-même dans son ensemble : dis "la table X"/"cette \
            table", JAMAIS "le champ X" ni "la colonne X" — ce sont deux natures d'objets différentes, jamais \
            interchangeables, au même titre que la règle 4 sur module/table.
            12. Si la question porte sur PLUSIEURS champs à la fois (ex. "quels sont les principaux champs de X \
            ?", "liste les champs de cette table") et que le contexte fournit plusieurs blocs "Colonne : ... \
            Description : ...", ne te contente JAMAIS d'une liste de noms nus : pour CHAQUE champ listé, ajoute \
            une courte description de son rôle tirée de sa Description (quelques mots suffisent, ex. "- \
            ACCOUNT.TITLE.1 : le nom/intitulé du compte"), jamais seulement "- ACCOUNT.TITLE.1". Si la \
            Description d'un champ précis manque dans le contexte, omets sa description plutôt que d'en \
            inventer une, mais garde quand même son nom dans la liste. Chaque tiret DOIT commencer sur sa propre \
            ligne (un vrai saut de ligne avant chaque "-", jamais plusieurs tirets à la suite dans le même \
            paragraphe) : jamais "Les champs sont : - X : ... - Y : ... - Z : ..." sur une seule ligne.
            13. Cas différent de la règle 12 : si PLUSIEURS champs DIFFÉRENTS (noms de Colonne différents, \
            éventuellement dans des tables différentes) répondent chacun plausiblement à LA MÊME question \
            précise posée (une vraie ambiguïté, pas une demande de liste), ne choisis JAMAIS silencieusement un \
            seul champ : présente-les tous, en précisant pour chacun son champ ET sa table, et en notant \
            brièvement leur différence fonctionnelle si les Description respectives le permettent. Termine par \
            une question naturelle invitant l'utilisateur à préciser lequel l'intéresse. Si un candidat est \
            clairement plus pertinent au vu de la question, présente-le comme le plus pertinent tout en \
            mentionnant les autres. Une fois que l'utilisateur précise lequel l'intéresse, réponds uniquement \
            sur celui-là, sans réénumérer les autres.
            14. Distingue toujours le degré de certitude de ta réponse, à partir de ce que le contexte permet \
            vraiment d'affirmer : si la Description confirme clairement l'information demandée, tu peux le dire \
            explicitement (ex. "c'est confirmé par la documentation"). Si le contexte donne un indice mais ne \
            confirme pas complètement (ex. Description absente, incomplète, ou ne répondant qu'indirectement à \
            la question), dis-le aussi clairement (ex. "la documentation indique ..., mais ne permet pas de \
            confirmer avec certitude que ..."). Ne transforme jamais une simple ressemblance de nom entre un \
            champ et les mots de la question en certitude — un nom qui semble correspondre ne suffit pas à \
            garantir que c'est la bonne réponse si sa Description ne le confirme pas vraiment.
            15. Si plusieurs blocs du contexte donnent des informations contradictoires sur le même champ/la \
            même table, ne les fusionne JAMAIS pour fabriquer une troisième réponse inventée à partir des deux : \
            signale la contradiction explicitement (ex. "les sources récupérées donnent deux informations \
            différentes : ..."), présente les deux, et indique laquelle semble la plus pertinente selon le \
            contexte de la question si tu peux le justifier à partir du contexte lui-même.
            16. Si un même champ (même nom de colonne) existe dans PLUSIEURS tables différentes du contexte, ne \
            les énumère JAMAIS à la suite dans une seule phrase (ex. "il se trouve dans les tables X, Y, Z et W") : \
            présente-les sous forme de liste, une table par ligne (tiret "- " en début de ligne), après une \
            courte phrase d'introduction.
            17. Réponds UNE SEULE FOIS, directement, sans aucun méta-commentaire sur ta propre réponse : ne \
            rédige jamais un brouillon suivi d'une version "améliorée", et n'utilise jamais d'étiquette comme \
            "Voici ma réponse", "Réponse :" ou toute autre formule de ce type — la réponse que tu donnes doit \
            être la version finale, sans reformulation ni doublon derrière.
            18. Une ligne "Colonne : X" dans le contexte désigne un CHAMP à l'intérieur d'une table, JAMAIS une \
            table à part entière : ne l'étiquette jamais "Table : X" ni ne la présente comme un élément d'une \
            liste de tables. Le nombre de tables/champs que tu énumères ne doit JAMAIS dépasser le nombre de \
            blocs "Table : "/"Colonne : " réellement présents dans le contexte fourni : si le contexte ne \
            couvre qu'une seule table, ta réponse ne peut porter que sur cette seule table, même si la question \
            semble en attendre plusieurs — dis-le clairement plutôt que de compléter la liste avec des noms \
            plausibles mais absents du contexte.
            19. Qualité de rédaction : français correct et professionnel (accords en genre et en nombre, \
            ponctuation soignée, aucun mot anglais hors identifiants T24). Garde une terminologie cohérente \
            ("champ", "table", "module", "enregistrement", "valeur") et conserve fidèlement les noms de \
            tables/champs/valeurs (MAJUSCULES avec points) ainsi que les termes techniques consacrés (SWIFT, \
            MT799, drill down...). Quand la réponse a plusieurs aspects, organise-la en courts paragraphes ou en \
            liste à tirets (un tiret par ligne) ; tu peux utiliser un Markdown simple : titres "## ", gras \
            "**mot**" et listes "- " (les tableaux sont ajoutés automatiquement, n'en écris pas). Termine par \
            une seule phrase de suite utile uniquement si elle découle du contexte (règle 8).
            20. Aucune répétition ni remplissage : chaque phrase apporte une information nouvelle tirée du \
            contexte, sans reformuler deux fois la même idée et sans conclusion "En résumé" qui répète ce qui \
            vient d'être dit. Ne commence jamais par une salutation ("Bonjour"). Si le contexte ne traite PAS du \
            sujet demandé (aucune Description qui y répond réellement), dis simplement qu'aucun élément \
            documenté ne correspond, sans présenter un champ voisin comme une réponse partielle ni supposer ce \
            qu'il pourrait faire.

            EXEMPLES (ces modules sont illustratifs, applique le même raisonnement à n'importe quel autre \
            module/table/colonne rencontré) :

            Exemple 1 — description présente dans le contexte, une seule table :
            Contexte :
            Module : TT
            Table : TFS.TRANSACTION
            Colonne : EXC.FROM.TXN.LIMIT
            Type : DATE
            Obligatoire : NON
            Description : Indique si ce type de transaction doit être exclu de la validation des limites ; si \
            renseigné à YES, la transaction ne sera pas comparée au plafond défini.
            Question : "EXC.FROM.TXN.LIMIT sert à quoi ?"
            RÉPONSE_ATTENDUE : "Selon la documentation T24, le champ EXC.FROM.TXN.LIMIT de la table \
            TFS.TRANSACTION permet d'exclure ce type de transaction de la validation des limites. Lorsqu'il est \
            renseigné à YES, la transaction n'est pas comparée au plafond défini et ne peut donc pas déclencher \
            de dépassement. La saisie de ce champ est facultative. N'hésitez pas à me demander si vous voulez \
            connaître d'autres champs de cette table."
            (Remarque : ne pas dire "table TELLER" ou toute autre table non citée dans le contexte.)

            Exemple 2 — aucune description utile dans le contexte :
            Contexte :
            Module : LD
            Table : AA.PRD.DES.OVERDUE
            Colonne : GRACE.DAYS
            Type : NON SPÉCIFIÉ
            Obligatoire : NON
            Description :
            Question : "GRACE.DAYS sert à quoi ?"
            RÉPONSE_ATTENDUE : "Selon la documentation T24, je ne trouve pas de description pour le champ \
            GRACE.DAYS : son rôle exact n'est pas précisé dans la documentation disponible. N'hésitez pas à \
            préciser votre question ou à consulter la documentation officielle BIAT."
            (Remarque : ne pas deviner un rôle plausible à partir du seul nom du champ.)

            Exemple 6 — question sur une TABLE entière (pas un champ précis), contexte sans ligne "Colonne : " :
            Contexte :
            Module : AC
            Table : ACCOUNT
            Description : The Account Module caters for the creation, maintenance and control of all types of \
            accounts handled by T24. Accounts can be classified as two types: Customer Accounts and Internal \
            Accounts.
            Question : "À quoi sert AC.ACCOUNT ?"
            RÉPONSE_ATTENDUE : "Selon la documentation T24, la table ACCOUNT sert à créer, gérer et contrôler tous \
            les types de comptes dans T24, qu'il s'agisse de comptes clients ou de comptes internes. N'hésitez \
            pas à me poser d'autres questions." (dire "la table ACCOUNT", jamais "le champ ACCOUNT" ni "le champ \
            AC.ACCOUNT" : aucune ligne "Colonne : " dans le contexte, donc le sujet est la table entière — \
            "AC." n'est que le préfixe du module déjà connu, à ignorer dans le nom répété en réponse.)

            Exemple 7 — question sur PLUSIEURS champs à la fois : chaque champ listé avec sa courte description, \
            jamais une liste de noms nus :
            Contexte :
            Module : AC
            Table : ACCOUNT
            Colonne : ACCOUNT.TITLE.1
            Description : Identifies the first part, or the whole, of the Account Title. Distinguishes \
            individual Accounts for a customer who holds more than one Account.
            ---
            Module : AC
            Table : ACCOUNT
            Colonne : CATEGORY
            Description : A 5-digit code identifying the category of the account, which determines its \
            currency, product type and applicable conditions.
            ---
            Module : AC
            Table : ACCOUNT
            Colonne : CUSTOMER
            Description : The customer number to which this account belongs.
            Question : "Quels sont les principaux champs d'un compte ?"
            RÉPONSE_ATTENDUE : "Selon la documentation T24, voici les principaux champs de la table ACCOUNT : \
            - ACCOUNT.TITLE.1 : l'intitulé du compte, qui permet de distinguer les comptes d'un même client s'il \
            en détient plusieurs. - CATEGORY : un code à 5 chiffres identifiant la catégorie du compte (devise, \
            type de produit, conditions applicables). - CUSTOMER : le numéro du client auquel appartient ce \
            compte. N'hésitez pas à me poser d'autres questions sur l'un de ces champs." (jamais "- \
            ACCOUNT.TITLE.1 - CATEGORY - CUSTOMER" sans description : chaque champ doit expliquer brièvement son \
            rôle à partir de sa propre Description.)

            Exemple 8 — plusieurs champs DIFFÉRENTS répondent chacun à LA MÊME question précise (vraie \
            ambiguïté, voir règle 13, différent de la règle 12 ci-dessus) :
            Contexte :
            Module : AC
            Table : ACCOUNT
            Colonne : CURRENCY
            Description : The currency of the account, used for all postings and balance calculations.
            ---
            Module : AC
            Table : ACCOUNT.CLOSURE
            Colonne : ACCT.LIQU.CURRENCY
            Description : The currency in which the account is liquidated when it is closed.
            Question : "Où trouve-t-on la devise du compte ?"
            RÉPONSE_ATTENDUE : "Selon la documentation T24, deux champs peuvent répondre à cette question : le \
            champ CURRENCY dans la table ACCOUNT, qui porte la devise générale utilisée pour toutes les \
            opérations et le calcul du solde du compte, et le champ ACCT.LIQU.CURRENCY dans la table \
            ACCOUNT.CLOSURE, qui ne concerne que la devise utilisée lors de la clôture du compte. Si vous \
            cherchez la devise courante du compte, c'est plutôt CURRENCY dans ACCOUNT. Vous vous intéressez à \
            l'un des deux en particulier ?" (ne pas répondre uniquement avec ACCT.LIQU.CURRENCY en ignorant \
            CURRENCY : les deux sont dans le contexte et répondent à la question, même si l'un est plus \
            directement pertinent.)

            Exemple 10 — un même champ existe dans plusieurs tables du contexte (voir règle 16) :
            Contexte :
            Module : AC
            Table : ACCOUNT.STATEMENT
            Colonne : CURRENCY
            Description : Identifies the Currency of the Account Statement.
            ---
            Module : AC
            Table : AC.EXTERNAL.CONTRACT
            Colonne : CURRENCY
            Description : Identifies the Currency of the external contract.
            Question : "et ce champ, il se trouve dans quelle table exactement ?"
            RÉPONSE_ATTENDUE : "Selon la documentation T24, le champ CURRENCY se trouve dans plusieurs tables du \
            module AC :
            - ACCOUNT.STATEMENT
            - AC.EXTERNAL.CONTRACT
            N'hésitez pas à me demander lequel vous intéresse en particulier." (jamais "il se trouve dans les \
            tables ACCOUNT.STATEMENT et AC.EXTERNAL.CONTRACT" en une seule phrase, et jamais de brouillon suivi \
            d'une version améliorée — une seule réponse, directement dans ce format en liste.)""";

    private static final String FALLBACK_SYSTEM_PROMPT = """
            Tu es un expert Temenos T24 chevronné. La documentation T24 locale de la banque ne contient rien de \
            pertinent pour cette question. Règles, dans l'ordre de priorité :

            0. Distingue toujours trois cas avant de répondre : (a) une question PERSONNELLE demandant une \
            donnée privée de l'utilisateur ou d'un tiers (salaire, solde, identité, dossier RH...) — tu n'as \
            JAMAIS accès à ce type de données, dis-le clairement, ne tente jamais de deviner ou de répondre par \
            politesse ; (b) une question totalement HORS DOMAINE, sans lien avec T24, la banque ou l'informatique \
            bancaire (ex. installer un logiciel, une recette de cuisine) — rappelle brièvement ta spécialité \
            (documentation technique Temenos T24) et invite à reformuler dans ce cadre, sans tenter d'y répondre \
            quand même ; (c) une vraie question générale de connaissances bancaires/T24 (concept, processus \
            métier, terminologie) — seul ce troisième cas suit les règles 1 à 7 ci-dessous.
            1. Pour une question générale (concept, processus métier, terminologie T24 ou bancaire), réponds \
            normalement et utilement avec tes connaissances générales, en français — tu es censé le savoir.
            2. N'invente JAMAIS un nom précis que tu ne connais pas avec certitude (nom exact d'une table, \
            d'une colonne, d'un champ, d'un paramètre ou d'une valeur) : pour ce niveau de détail technique T24, \
            dis que tu ne peux pas le garantir sans la documentation officielle BIAT, plutôt que d'inventer.
            3. Commence ta réponse en indiquant, avec tes propres mots (formulation variée, jamais la même \
            phrase à chaque fois), que cette information vient de connaissances générales et non de la \
            documentation officielle BIAT, puis donne une réponse concise, directe et utile.
            4. Si un historique de conversation est fourni, traite la conversation comme un seul fil continu : \
            utilise-le pour comprendre les questions de suivi elliptiques, sans réexpliquer ce qui a déjà été \
            dit ; réponds uniquement à la question actuelle. Si la question actuelle change clairement de sujet \
            par rapport à l'échange précédent, signale-le naturellement en tout début de réponse (ex. "D'accord, \
            on passe maintenant à X, ") avant de répondre, plutôt que de basculer silencieusement.
            5. Ne dis "je ne sais pas" que pour un détail technique précis que tu ignores réellement — jamais \
            pour une question générale sur les concepts ou processus T24/bancaires.
            6. Tu peux terminer par une seule question ou piste de suivi, uniquement si elle porte sur un \
            concept général déjà évoqué dans l'échange (jamais un détail technique précis inventé) — sinon \
            invite simplement l'utilisateur à poser d'autres questions.
            7. Réponds intégralement en français, y compris pour les termes ou expressions métier anglo-saxons \
            (ex. "off-balance sheet" -> "hors bilan") : ne laisse jamais un mot ou une expression en anglais \
            dans ta réponse, sauf un identifiant T24 exact (nom de module, table, colonne, valeur de champ).""";

    private final RestClient restClient;
    private final String model;

    public AnswerServiceImpl(@Value("${biat.ollama.base-url:http://localhost:11434}") String baseUrl,
                              @Value("${biat.ollama.model:llama3.2:3b}") String model) {
        this.model = model;
        // Le modèle local (CPU, sans GPU sur cette machine) peut être lent : une simple requête triviale a déjà
        // pris ~13s en pratique, donc un lot de 15 descriptions à traduire peut largement dépasser 60s.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(300_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** Demande explicite de brièveté dans le message courant : un petit modèle local suit peu fiablement une
     *  consigne de style seulement déduite de l'historique, donc on la rend explicite et prioritaire ici. */
    private static final Pattern BREVITY_REQUEST_PATTERN = Pattern.compile(
            "br[iè]ve?ment|en bref|sois concis|plus court|en une phrase|r[ée]sume|sans d[ée]tail",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static String withBrevityOverride(String systemPrompt, String question) {
        if (question == null || !BREVITY_REQUEST_PATTERN.matcher(question).find()) return systemPrompt;
        return systemPrompt + "\n\nConsigne prioritaire pour cette réponse précise : l'utilisateur demande "
                + "explicitement une réponse brève. Réponds en une ou deux phrases maximum, va droit à l'essentiel, "
                + "ignore pour cette fois toute consigne de détail exhaustif donnée plus haut.";
    }

    /**
     * Capture le nom qui suit immédiatement "champ"/"colonne"/"table" (ex: "le champ **CHARGE.XCHG**
     * indique...", ou "le champ « CUSTOMER »") — c'est-à-dire l'identifiant que la réponse désigne comme SUJET de
     * l'explication, pas une simple mention secondaire au fil du texte (ex. un autre champ référencé dans la
     * description elle-même, légitime à citer sans être le sujet répondu). Voir {@link #citesOnlyContextIdentifiers}.
     * <p>Accepte soit un nom pointé (ex. CHARGE.XCHG), soit un mot nu mais entièrement en MAJUSCULES (ex.
     * CUSTOMER, au moins 3 caractères) — de nombreux champs T24 courants (CUSTOMER, CURRENCY, ACCOUNT, AMOUNT...)
     * n'ont pas de point : sans cette seconde forme, une hallucination sur UN DE CES NOMS passait entièrement
     * inaperçue (observé en pratique : contexte montrant le champ ACCOUNT, réponse citant "CUSTOMER" à la place,
     * jamais rejetée puisque "CUSTOMER" n'est pas pointé). L'exigence de MAJUSCULES (comparaison sensible à la
     * casse, voir {@code (?-iu:...)}) évite qu'un mot français ordinaire suivant "champ" (ex. "le champ \
     * concerné") ne soit pris à tort pour un identifiant.
     */
    private static final Pattern SUBJECT_FIELD_PATTERN = Pattern.compile(
            "(?iu)\\b(champ|colonne|table)\\s+«?\\*{0,2}("
                    + "[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+"
                    + "|(?-iu:[A-Z][A-Z0-9]{2,}(?: [A-Z][A-Z0-9]{2,})?))"
                    + "\\*{0,2}»?");

    /**
     * Capture l'identifiant introduisant un élément de liste à tirets (ex. "- LD.LOANS.PAYMENTS : contient...") —
     * mêmes deux formes acceptées que {@link #SUBJECT_FIELD_PATTERN} (pointée ou mot nu en MAJUSCULES). Une liste
     * de tables/champs énumérés de cette façon (règle 12/16 du system prompt) échappait entièrement à {@link
     * #citesOnlyContextIdentifiers}, qui ne contrôlait que la tournure "champ/colonne/table X" : observé en
     * pratique, un contexte ne couvrant réellement qu'UNE table listait malgré tout 3 autres tables plausibles
     * mais absentes du contexte, jamais rejetées faute de ce contrôle sur les tirets.
     */
    private static final Pattern DASH_LIST_SUBJECT_PATTERN = Pattern.compile(
            "(?iu)(?:^|\\s)-\\s+(" + "[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+"
                    + "|(?-iu:[A-Z][A-Z0-9]{2,}(?: [A-Z][A-Z0-9]{2,})?))\\s*:",
            Pattern.MULTILINE);

    /**
     * Ligne de liste, quel que soit le style utilisé par le petit modèle local (tiret, astérisque, ou numérotée
     * "1." / "1)") — repère volontairement large et indépendant de la ponctuation exacte autour de l'identifiant
     * (contrairement à {@link #DASH_LIST_SUBJECT_PATTERN}/{@link #SUBJECT_FIELD_PATTERN}, qui ratent un format
     * jamais anticipé, ex. "1. TABLE : CUSTOMER ID"). Sert uniquement à COMPTER les éléments énumérés, voir
     * {@link #hasImplausibleListLength}.
     */
    private static final Pattern LIST_ITEM_LINE_PATTERN = Pattern.compile("(?m)^\\s*(?:[-*]|\\d+[.)])\\s+\\S");

    /**
     * Vrai si {@code answer} énumère significativement PLUS d'éléments que de documents réellement retrouvés
     * ({@code context.size()}, au plus {@code topK}) — un signal fort et indépendant du format exact utilisé
     * (contrairement aux vérifications par motif ci-dessus, contournées à chaque nouvelle mise en forme du petit
     * modèle local) : le contexte fourni ne peut par construction jamais justifier une liste beaucoup plus longue
     * que lui-même, quelle que soit la ponctuation utilisée. Observé en pratique : contexte d'une seule table
     * réelle (LD.LOANS.AND.DEPOSITS), réponse énumérant 54 "tables" — en réalité les champs de cette unique table,
     * mal étiquetés "TABLE :" un par un plutôt que présentés comme les colonnes d'une seule table. Marge de +2
     * pour ne jamais rejeter à tort une énumération légitime un peu plus fine que le nombre brut de documents
     * (ex. un document décrivant plusieurs champs à la fois).
     * <p>Seuil plancher à {@link #MIN_LIST_ITEMS_ALLOWED} (pas seulement {@code contextSize + 2}) : une question
     * FIELD (un seul document de contexte, le champ exact demandé) invite légitimement, via {@code FIELD_REMINDER},
     * à détailler PLUSIEURS propriétés documentées de CE MÊME champ (type, obligatoire, format, longueur, valeurs
     * autorisées, valeur par défaut, interdictions...) en autant de puces — ce n'est pas une énumération de
     * plusieurs SUJETS distincts (la seule chose que ce contrôle doit détecter), donc jamais un signe
     * d'invention. Observé en pratique : sans ce plancher, une réponse détaillée et fidèle sur un seul champ
     * (5-6 puces de propriétés) était rejetée à tort par ce seul contrôle, alors qu'aucun identifiant hors
     * contexte n'y était cité ({@link #citesOnlyContextIdentifiers} l'aurait détecté sinon).
     */
    private static final int MIN_LIST_ITEMS_ALLOWED = 8;

    /** Vrai si le contexte contient la vue d'ensemble d'une table : ses champs peuvent alors légitimement être
     *  énumérés en bien plus grand nombre que de documents retrouvés (un document par champ n'est pas garanti). */
    static boolean hasTableOverview(List<RetrieverService.ScoredDocument> context) {
        return context.stream().anyMatch(c -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(c.document().getColumnName()));
    }

    static boolean hasImplausibleListLength(String answer, int contextSize) {
        if (answer == null) return false;
        Matcher m = LIST_ITEM_LINE_PATTERN.matcher(answer);
        int count = 0;
        while (m.find()) count++;
        return count > Math.max(contextSize + 2, MIN_LIST_ITEMS_ALLOWED);
    }

    /** Taille maximale (en caractères) d'un document envoyé au modèle : la vue d'ensemble d'une grande table peut
     *  dépasser 10 000 caractères, ce qui rend la génération très lente sur CPU sans améliorer la réponse. */
    private static final int MAX_CONTEXT_DOC_CHARS = 2500;

    /** Longueur maximale conservée de la clause "Validation Rules" située en fin de Description. */
    private static final int MAX_VALIDATION_TAIL_CHARS = 700;

    /**
     * Limite la taille d'un document du contexte, en gardant TOUJOURS la clause "Validation Rules" qui termine la
     * Description : une coupe naïne au début du texte supprimait précisément les règles de validation des champs à
     * longue description (donc la réponse aux questions sur le contrôle de saisie).
     */
    static String truncateForContext(String text) {
        if (text.length() <= MAX_CONTEXT_DOC_CHARS) return text;
        int rules = text.lastIndexOf("Validation Rules");
        if (rules < 0 || rules < MAX_CONTEXT_DOC_CHARS - MAX_VALIDATION_TAIL_CHARS) {
            return text.substring(0, MAX_CONTEXT_DOC_CHARS);
        }
        String tail = text.substring(rules, Math.min(text.length(), rules + MAX_VALIDATION_TAIL_CHARS));
        return text.substring(0, MAX_CONTEXT_DOC_CHARS - MAX_VALIDATION_TAIL_CHARS) + " [...] " + tail;
    }

    /** Début d'un paragraphe de conclusion "En résumé, ..." (jusqu'à la fin de la réponse) : voir generate. */
    private static final Pattern SUMMARY_TAIL_PATTERN = Pattern.compile("(?iu)\\n\\s*(?:en r[ée]sum[ée]|en conclusion)[\\s,:][\\s\\S]*$");

    /** Le modèle constate lui-même l'absence d'information ("il n'y a pas de champ ...") : voir generate. */
    private static final Pattern NEGATIVE_FINDING_PATTERN = Pattern.compile(
            "(?iu)^.{0,60}(?:il n'y a (?:pas|aucun)|aucun (?:champ|[ée]l[ée]ment|table)|n'existe pas|pas de champ)");

    @Override
    public String generate(String question, List<RetrieverService.ScoredDocument> context, String conversationHistory,
                            String module, QuestionIntent intent) {
        // Réponse déterministe pour TOUTE question VALIDATION (une ou plusieurs tables), jamais confiée au LLM :
        // observé en pratique (champ BEN.CUSTNO, table LC.BENEFICIARY) qu'avec une seule table, le petit modèle
        // local ignore parfois la consigne et répond par la DÉFINITION du champ au lieu de sa règle de validation
        // pourtant bien présente dans la documentation (« 1-6 numeric character Customer Code... ») — le même
        // risque que pour le cas à plusieurs tables (règles inventées ou mélangées, voir perTableValidationAnswer),
        // qui avait déjà justifié ce court-circuit sans appel LLM.
        if (intent == QuestionIntent.VALIDATION) {
            String deterministic = perTableValidationAnswer(question, context);
            if (deterministic != null) return deterministic;
        }
        String contextText = context.stream()
                .map(c -> MandatoryClassifier.withFreshMandatoryLine(c.document().getContent()))
                .map(AnswerServiceImpl::truncateForContext)
                .collect(Collectors.joining("\n---\n"));
        boolean moduleMode = context.stream()
                .anyMatch(c -> Document.MODULE_SUMMARY_COLUMN_MARKER.equals(c.document().getColumnName()));
        // Appel LLM dédié, séparé de la boucle de génération des rubriques ci-dessous (voir moduleSynthesisIntro) :
        // une tâche à la fois, plus fiable pour le petit modèle local qu'un seul prompt combinant synthèse
        // rédactionnelle et structuration en rubriques.
        String moduleIntro = moduleMode ? moduleSynthesisIntro(context) : "";
        String moduleSource = context.stream().map(c -> c.document().getContent()).collect(Collectors.joining(" "));
        Set<String> knownModuleTables = moduleMode
                ? context.stream().map(RetrieverService.ScoredDocument::document)
                        .filter(d -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                        .map(d -> d.getTableName().toUpperCase())
                        .collect(Collectors.toSet())
                : Set.of();
        // Tables ET champs réellement distincts parmi les documents retrouvés (hors moduleMode, qui a son propre
        // filtre ci-dessus) : voir keepOnlyKnownIdentifiers, le même filet de sécurité que knownModuleTables mais
        // pour une réponse générale (TOPIC_TABLES, OTHER...) qui énumère des tables ET des champs en puces.
        Set<String> knownIdentifiers = moduleMode ? Set.of() : context.stream()
                .map(RetrieverService.ScoredDocument::document)
                .flatMap(d -> {
                    List<String> ids = new ArrayList<>();
                    if (d.getTableName() != null) ids.add(d.getTableName().toUpperCase());
                    if (d.getColumnName() != null
                            && !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName())
                            && !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(d.getColumnName())) {
                        ids.add(d.getColumnName().toUpperCase());
                    }
                    return ids.stream();
                })
                .collect(Collectors.toSet());
        String prompt = (conversationHistory != null && !conversationHistory.isBlank()
                ? "Historique de la conversation :\n" + conversationHistory + "\n\n"
                : "")
                + "Contexte :\n" + contextText + "\n\nQuestion : " + question
                + reminderFor(question, context, conversationHistory, intent, moduleMode);
        Map<String, Object> body = Map.of("model", model, "system", withBrevityOverride(SYSTEM_PROMPT, question),
                "prompt", prompt, "stream", false, "options", ANSWER_OPTIONS);

        // Même risque qu'en reformulation de requête (voir isSafeRewrite) : le petit modèle local peut citer un
        // nom de champ/table plausible mais absent du contexte fourni (observé en pratique : contexte montrant
        // "Colonne : RATE.BOOKED", réponse citant "CHARGE.XCHG" à la place) — jusqu'à 2 tentatives complètes avant
        // d'abandonner (chacune avec son propre retry réseau via postGenerateWithRetry), pour laisser l'appelant
        // basculer sur le texte brut garanti fidèle au contexte (voir ChatController) plutôt que d'afficher une
        // réponse qui contredit la carte réellement retrouvée.
        String lastImperfectButValid = null;
        // Seconde tentative après une réponse fidèle mais incomplète/trop brève : on le dit au modèle (sinon, à
        // température basse, il reproduit presque la même réponse).
        Map<String, Object> retryBody = Map.of("model", model, "system", withBrevityOverride(SYSTEM_PROMPT, question),
                "prompt", prompt + "\n\nTa réponse précédente était incomplète ou trop brève : reprends-la en la "
                        + "développant davantage, uniquement à partir du contexte.",
                "stream", false, "options", ANSWER_OPTIONS);
        for (int attempt = 1; attempt <= 2; attempt++) {
            GenerateResponse response = postGenerateWithRetry(attempt == 2 && lastImperfectButValid != null ? retryBody : body);
            String answer = response == null ? null : removeRepetitions(cutInventedTurns(response.response()));
            if (answer != null && "length".equals(response.doneReason())) answer = dropCutOffLastLine(answer);
            if (answer == null) return lastImperfectButValid;
            answer = autoCorrectNearMissIdentifiers(answer, context);
            if (!citesOnlyContextIdentifiers(answer, context, module, conversationHistory)) {
                log.warn("Réponse LLM citant un identifiant absent du contexte (tentative {}/2) : {}", attempt, answer);
                continue;
            }
            // (Une liste de règles de validation, d'exemples ou de valeurs d'un même document peut légitimement
            // compter bien plus d'éléments que de documents : contrôle réservé aux questions "classiques".)
            if (!moduleMode && (intent == null || intent == QuestionIntent.OTHER)
                    && hasImplausibleListLength(answer, context.size()) && !hasTableOverview(context)) {
                log.warn("Réponse LLM énumérant beaucoup plus d'éléments que le contexte ({} documents) ne le "
                        + "justifie (tentative {}/2) : {}", context.size(), attempt, answer);
                continue;
            }
            if (looksLikeEnglish(answer)) {
                log.warn("Réponse LLM en anglais malgré la consigne (tentative {}/2) : {}", attempt, answer);
                continue;
            }
            if (moduleMode) answer = normalizeModuleHeadings(answer);
            // Réponse MODULE_GENERAL sans AUCUN titre "## " (le LLM a écrit les intitulés de rubrique en texte
            // brut) : {@link #extractLlmModuleIntro} et {@link #isolateModuleRubrics} dégénèrent alors TOUS LES
            // DEUX en renvoyant le texte entier (faute de savoir où le couper), dupliquant intégralement la
            // réponse (intro + "rubriques" identiques, concaténées) — mieux vaut retenter que produire ça.
            if (moduleMode && !answer.contains("##")) {
                log.warn("Réponse LLM sans rubriques Markdown \"## \" (tentative {}/2) : {}", attempt, answer);
                continue;
            }
            String cleaned = ensureBlankLineBeforeHeadings(stripEmptyModuleSections(stripGenericFillerSentences(
                    fixFrenchElisions(formatDashListOnNewLines(stripDraftLabel(stripBulletAsterisks(
                            stripClosingInvitation(stripPriorQuestionPreamble(fixHeaderlessTables(answer))))))))));
            // Une question de suivi n'a pas besoin de répéter la formule de sourçage à chaque tour.
            // Réponse MODULE_GENERAL : le fait de rattachement (code + VRAI libellé officiel) est déterministe et
            // ne peut donc jamais être inventé (ex. "Electronic Banking" alors que le libellé réel de EB est
            // "System Core") ; la CONCLUSION reste elle aussi déterministe (jamais d'auto-évaluation du type "ce
            // module est une composante clé"). Entre les deux, la véritable DÉFINITION FONCTIONNELLE reste
            // rédigée par un appel LLM DÉDIÉ (voir moduleSynthesisIntro, calculé une seule fois avant cette
            // boucle) — un fait comme le libellé officiel se vérifie mécaniquement, mais une explication
            // pédagogique du rôle du module ne peut pas être pré-écrite sans réintroduire exactement le problème
            // inverse (un inventaire à la place d'une définition).
            String grounded = moduleMode
                    ? moduleAnchorFact(context) + " " + moduleIntro + "\n\n"
                            + dedupTablesAcrossRubrics(keepOnlyKnownTables(
                                    dropUnsupportedLines(isolateModuleRubrics(cleaned), moduleSource), knownModuleTables))
                            + MODULE_CLOSING_LINE
                    : dropUnrequestedMandatoryClaims(keepOnlyKnownIdentifiers(
                            intent == QuestionIntent.VALIDATION && context.stream()
                                    .map(c -> c.document().getTableName()).distinct().count() <= 1
                                    ? firstSentenceOnly(ensureGroundedOpening(cleaned))
                                    : ensureGroundedOpening(cleaned),
                            knownIdentifiers), question);
            // Le modèle constate que rien ne correspond, puis se met à décrire des champs voisins sans rapport
            // (spéculation non fondée sur la source) : seule la constatation est conservée.
            if (NEGATIVE_FINDING_PATTERN.matcher(grounded).find()) {
                int end = grounded.indexOf(". ");
                if (end > 0) grounded = grounded.substring(0, end + 1);
            }
            // Question d'existence : si la réponse contient la constatation d'absence, elle est la réponse (le reste
            // décrit des champs voisins sans rapport avec la question).
            if (EXISTENCE_QUESTION_PATTERN.matcher(question).find()) {
                Matcher neg = NEGATIVE_SENTENCE_PATTERN.matcher(grounded);
                if (neg.find()) grounded = ensureGroundedOpening(neg.group().trim());
            }
            // Paragraphe final "En résumé, ..." : simple répétition du corps de la réponse (règle 20), retiré
            // tant qu'il reste une réponse substantielle avant lui.
            Matcher tail = SUMMARY_TAIL_PATTERN.matcher(grounded);
            if (tail.find() && grounded.substring(0, tail.start()).trim().split("\\s+").length >= 40) {
                grounded = grounded.substring(0, tail.start()).trim();
            }
            // Un même champ présent dans plusieurs tables du contexte (règle 16) : le petit modèle local en
            // "oublie" parfois une en la retranscrivant (observé en pratique : 5 tables dans le contexte, 4 \
            // listées dans la réponse) — sans lien avec une hallucination (les 4 citées sont bien réelles), donc
            // {@link #citesOnlyContextIdentifiers} ne le détecte pas. Une nouvelle tentative avant d'accepter
            // quand même la réponse incomplète plutôt que de basculer sur le texte brut du contexte.
            if (intent == QuestionIntent.OTHER && !moduleMode && !mentionsAllContextTables(grounded, context)) {
                log.warn("Réponse LLM omettant une ou plusieurs tables du contexte (tentative {}/2) : {}", attempt, grounded);
                lastImperfectButValid = grounded;
                continue;
            }
            // Règle 12 (chaque élément d'une liste doit avoir sa description) : le petit modèle local retombe
            // parfois sur une liste de noms nus sans aucune explication (observé en pratique : "- CUSTOMER \n -
            // ACCOUNT.NUMBER \n - ..." sans un seul mot de description) — ne cite aucun identifiant hors contexte
            // donc {@link #citesOnlyContextIdentifiers} ne le détecte pas, mais viole quand même la consigne de
            // fond. Une nouvelle tentative avant d'accepter quand même la liste nue plutôt que le texte brut.
            if (hasBareListItems(grounded)) {
                log.warn("Réponse LLM listant des noms nus sans description (tentative {}/2) : {}", attempt, grounded);
                lastImperfectButValid = grounded;
                continue;
            }
            // La règle 2 du system prompt (expliquer, pas juste nommer) n'est pas toujours suivie par ce petit
            // modèle local pour une question de type "quel champ permet de..." (observé en pratique : réponse
            // réduite au seul nom du champ) — une seule nouvelle tentative avant d'accepter quand même la
            // réponse (courte mais fidèle au contexte) plutôt que de basculer sur le texte brut du contexte.
            if (!isTooTerse(question, grounded, context)) return grounded;
            log.warn("Réponse LLM trop brève par rapport au contexte disponible (tentative {}/2) : {}", attempt, grounded);
            lastImperfectButValid = grounded;
        }
        return lastImperfectButValid;
    }

    /** Vrai si {@code answer} mentionne bien TOUTES les tables distinctes présentes dans {@code context}, quand
     *  il y en a plusieurs (voir règle 16 et {@link #generate}) — sans effet (toujours vrai) s'il n'y a qu'une
     *  seule table, ce contrôle ne visant que le cas d'un même champ réparti sur plusieurs tables. */
    private static boolean mentionsAllContextTables(String answer, List<RetrieverService.ScoredDocument> context) {
        Set<String> tables = context.stream()
                // "ACCOUNT,ISB" est une variante locale de "ACCOUNT" : une seule table pour la réponse.
                .map(c -> c.document().getTableName().toUpperCase().replaceAll(",[A-Z]+$", ""))
                .collect(Collectors.toSet());
        if (tables.size() < 2 || answer == null) return true;
        String upper = answer.toUpperCase();
        return tables.stream().allMatch(upper::contains);
    }

    /** Une ligne de liste ("- NOM : ..."), pour {@link #hasBareListItems} — même famille que {@link
     *  #DASH_LIST_SUBJECT_PATTERN} mais capture aussi le texte après le ":" pour en juger la substance. */
    private static final Pattern DASH_LIST_LINE_PATTERN = Pattern.compile("(?m)^\\s*-\\s+\\S.*$");

    /** Nombre minimal de mots après le ":" d'un élément de liste pour le considérer comme réellement décrit (voir
     *  règle 12) — volontairement bas (une description peut légitimement être courte), le but est seulement de
     *  distinguer un nom nu ("- CUSTOMER") d'une vraie explication, même brève. */
    private static final int MIN_WORDS_PER_LIST_ITEM_DESCRIPTION = 3;

    /**
     * Vrai si {@code answer} contient une liste à tirets dont AUCUN élément n'a de description substantielle
     * après son ":" (règle 12) — observé en pratique : une liste de noms nus ("- CUSTOMER", "- ACCOUNT.NUMBER"...)
     * sans une seule explication. Volontairement strict (déclenché seulement si ZÉRO élément est décrit, pas dès
     * qu'un seul l'est) : une liste où au moins un élément est décrit n'est pas ce raté précis, pas la peine de
     * la rejeter pour autant.
     */
    private static boolean hasBareListItems(String answer) {
        if (answer == null) return false;
        Matcher m = DASH_LIST_LINE_PATTERN.matcher(answer);
        int itemCount = 0;
        int describedCount = 0;
        while (m.find()) {
            itemCount++;
            String line = m.group();
            int colonIdx = line.indexOf(':');
            String afterColon = colonIdx >= 0 ? line.substring(colonIdx + 1).trim() : "";
            if (afterColon.split("\\s+").length >= MIN_WORDS_PER_LIST_ITEM_DESCRIPTION) describedCount++;
        }
        return itemCount > 0 && describedCount == 0;
    }

    /** Une question qui semble n'attendre qu'un NOM de champ/table (ex. "quel champ permet d'identifier le \
     *  compte ?") appelle malgré tout une explication substantielle (règle 2 du system prompt) : signale une \
     *  réponse trop courte pour ce type de question précis, pour déclencher une nouvelle tentative de génération \
     *  plutôt que d'accepter une réponse réduite au seul nom. Ne s'applique jamais à une demande explicite de \
     *  brièveté (voir {@link #BREVITY_REQUEST_PATTERN}), qui doit rester courte. */
    private static final Pattern NAMING_QUESTION_PATTERN = Pattern.compile(
            "(?iu)\\bquel(?:le)?s?\\s+(champ|colonne|table)s?\\b.{0,40}\\b(permet|sert|contient|indique|identifie|repr[ée]sente)");

    private static final int MIN_SUBSTANTIVE_WORDS = 18;

    /** Rappel de forme ajouté juste après la question (un petit modèle suit mieux une consigne placée en dernier
     *  qu'une règle lointaine du prompt système) : développer à partir de TOUTE la Description du contexte. */
    private static final String DEPTH_REMINDER = "\n\nRappel : réponds en français correct et professionnel, de "
            + "façon développée et structurée, en t'appuyant sur TOUS les éléments utiles de la Description du "
            + "contexte (rôle, format, règles de validation, valeurs autorisées, caractère obligatoire), sans "
            + "rien inventer, sans supposition (jamais \"il est probable que\"), sans conclusion \"En résumé\", "
            + "sans citer la Description anglaise entre guillemets (traduis-la) et sans Markdown.";

    /** Question demandant plusieurs champs/tables à la fois : la réponse est une liste, pas l'explication d'un seul. */
    private static final Pattern LIST_QUESTION_PATTERN = Pattern.compile(
            "(?iu)\\b(les champs|quels champs|liste|principaux champs|champs de|champs qui|les tables|quelles tables)\\b");

    private static final String LIST_REMINDER = "\n\nRappel : réponds en français correct par une liste à tirets "
            + "(un tiret par ligne, un élément par champ ou table présent dans le contexte), chaque élément suivi "
            + "d'une courte description en FRANÇAIS (traduite depuis sa Description anglaise, jamais recopiée telle "
            + "quelle), sans rien inventer.";

    private static final String FOLLOWUP_REMINDER = "\n\nRappel : cette question prolonge l'échange précédent. "
            + "Réponds uniquement à la nouvelle question, en 2 à 4 phrases en français, sans réexpliquer ce qui a "
            + "déjà été dit sur ce champ ou cette table, sans rien inventer.";

    private static final String NO_INVENTED_TURNS = " N'écris jamais de question à la place de l'utilisateur ni de "
            + "ligne \"Question :\" ou \"Utilisateur :\".";

    private static final String MODULE_REMINDER = "\n\nRappel : la question porte sur le MODULE dans son ensemble "
            + "(pas sur un champ ni sur une table isolée : le sujet est ici le module, malgré l'absence de ligne "
            + "\"Colonne :\"). N'écris NI introduction NI phrase de politesse NI conclusion (une phrase de "
            + "rattachement et une présentation générale sont déjà ajoutées automatiquement avant ta réponse) : "
            + "commence directement par la première rubrique \"## \" et arrête-toi juste après la dernière puce.\n"
            + "Entre 2 et 4 rubriques (## Titre), choisies UNIQUEMENT parmi celles qui correspondent vraiment aux "
            + "tables du contexte, parmi : \"Tables et Données\", \"Processus et Flux de travail\", "
            + "\"Configuration et Paramétrage\", \"Utilisateurs et Documentation\" (n'utilise jamais un autre "
            + "intitulé, et omets celles dont aucune table du contexte ne relève — jamais de liste ni de phrase "
            + "de remplissage pour une rubrique sans contenu pertinent). Ne sélectionne une table QUE si elle "
            + "apporte vraiment quelque chose à la compréhension du module (jamais uniquement parce que son nom "
            + "commence par le code du module). Sous chaque rubrique, une liste à tirets \"- NOM.TABLE : rôle\" "
            + "(au plus 6 tables au total, CHAQUE table dans UNE SEULE rubrique — la plus pertinente pour elle — "
            + "jamais répétée sous plusieurs titres, uniquement celles dont la vue d'ensemble figure dans le "
            + "contexte) où \"rôle\" explique en DEUX temps, en langage courant et compréhensible par un lecteur "
            + "NON technique : d'abord sa fonction concrète (à quoi elle sert dans la vraie vie, jamais juste ce "
            + "qu'elle \"contient\" ni une paraphrase creuse de son nom), puis — UNIQUEMENT si le contexte permet "
            + "de l'établir réellement — comment elle s'intègre au fonctionnement du module (quels autres "
            + "éléments l'utilisent ou en dépendent). Cite les vrais noms T24 utiles (tables, champs) — ne les "
            + "omets jamais, ils restent la preuve vérifiable de la réponse — mais JAMAIS en liste nue énumérée "
            + "sans explication (ex. \"STMT.ENTRY, CATEG.ENTRY, CONSOL.NET.TODAY\" enchaînés sans un mot sur ce "
            + "qu'ils sont) : dis d'abord ce que chaque nom cité représente concrètement (ex. \"le fichier des "
            + "mouvements de compte (STMT.ENTRY)\"), pas l'inverse. Un ou deux noms bien expliqués valent mieux "
            + "qu'une longue liste. Chaque rôle est tiré de la vue d'ensemble de la table et traduit "
            + "intégralement en français (aucun mot anglais, même isolé, y compris \"this/these/process/"
            + "workflow\" : traduis toute la phrase). N'entre pas dans le détail d'un champ précis, sans rien "
            + "inventer." + NO_INVENTED_TURNS;

    /** Distingue une IDENTIFICATION d'une seule table ("quelle table est responsable de X ?", "quelle table gère
     *  X ?") d'une vraie recherche thématique multi-tables ("quelles tables gèrent les paiements ?") : la première
     *  n'appelle qu'une réponse courte (nom de la table + rôle), jamais la liste de ses champs — bug trouvé en
     *  pratique : la réponse à "quelle table est responsable de balances ?" citait correctement MD.BALANCES, mais
     *  ajoutait ensuite 5 champs non demandés (TOT.CHARGE.CCY, PAST.SCHED.DATE...), rendant la réponse inutilement
     *  longue. Le singulier "table" (pas "tables") suivi de "est/sont" est le signal retenu : une vraie question
     *  thématique multi-tables s'exprime au pluriel ("quelles tables ... ?"). */
    private static final Pattern SINGLE_TABLE_LOOKUP_PATTERN = Pattern.compile(
            "(?iu)\\bquel(?:le)?\\s+tables?\\s+(est|sont)\\b");

    private static final String TABLE_LOOKUP_REMINDER = "\n\nRappel : la question demande à IDENTIFIER une seule "
            + "table précise, pas une liste thématique. Réponds directement et brièvement, en 1 à 2 phrases : le nom "
            + "exact de la table la plus pertinente du contexte, et son rôle en une phrase tirée de sa vue "
            + "d'ensemble. N'énumère PAS ses champs, sauf si la question les demande explicitement. Sans "
            + "introduction, sans conclusion, sans rien inventer." + NO_INVENTED_TURNS;

    private static final String TOPIC_TABLES_REMINDER = "\n\nRappel : la question demande les tables du module en "
            + "lien avec un thème précis, avec pour chacune son rôle et ses champs principaux. Réponds directement "
            + "par une section par table pertinente du contexte (jamais une autre, et sans aucune phrase "
            + "d'introduction générale avant la première) : un titre \"### NOM.TABLE\" (nom exact), une phrase de "
            + "rôle (tirée de sa vue d'ensemble), puis les champs principaux réellement présents dans le contexte "
            + "pour cette table, en puces SIMPLES d'un seul niveau \"- NOM.CHAMP : rôle en une courte phrase\" "
            + "(jamais de sous-puces), traduit en français, jamais recopié en anglais. N'invente aucune table ni "
            + "aucun champ absent du contexte ; si aucune table du contexte ne correspond vraiment au thème "
            + "demandé, dis-le clairement au lieu d'en forcer une. Sans conclusion \"En résumé\"." + NO_INVENTED_TURNS;

    private static final String AMBIGUOUS_REMINDER = "\n\nRappel : la question est peu précise et plusieurs champs "
            + "différents du contexte peuvent y correspondre. N'en choisis pas un seul : présente en 2 à 4 puces les "
            + "champs les plus pertinents (\"- NOM.CHAMP (table) : rôle en une phrase\"), uniquement ceux du contexte, "
            + "puis demande à l'utilisateur de préciser lequel l'intéresse. Sans rien inventer." + NO_INVENTED_TURNS;

    private static final Pattern DOTTED_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+");

    /** Question courte, sans identifiant T24 ni historique, dont le contexte mélange au moins 3 champs différents. */
    private static boolean isAmbiguous(String question, List<RetrieverService.ScoredDocument> context,
                                        String conversationHistory) {
        if (hasPriorTurns(conversationHistory)) return false;
        if (DOTTED_TOKEN.matcher(question).find() || question.trim().split("\\s+").length > 9) return false;
        long distinctColumns = context.stream()
                .map(c -> c.document().getColumnName())
                .filter(n -> n != null && !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(n))
                .map(String::toUpperCase).distinct().count();
        return distinctColumns >= 3;
    }

    private static final String TABLE_REMINDER = "\n\nRappel : la question porte sur une TABLE. Rédige en français : "
            + "une introduction expliquant son rôle (à partir de sa vue d'ensemble), ses principales "
            + "fonctionnalités documentées et les grandes catégories de champs qu'elle contient. Ne détaille pas "
            + "chaque champ : un tableau des champs est ajouté automatiquement après ta réponse. Sans conclusion "
            + "\"En résumé\", sans rien inventer.";

    private static final String FIELD_REMINDER = "\n\nRappel : la question porte sur un CHAMP. Réponds en 1 à 2 "
            + "phrases complètes sur son rôle et son fonctionnement, sans liste ni rubrique. Ne cite ni les valeurs "
            + "autorisées, ni le type, ni les règles de validation, ni le caractère obligatoire/facultatif, ni une "
            + "éventuelle valeur par défaut, sauf si la question le demande explicitement. "
            + "Sans formule de politesse ni de conclusion, sans rien inventer.";

    private static final String VALIDATION_REMINDER = "\n\nRappel : la question porte sur une propriété précise "
            + "d'un champ (contrôle de saisie). Réponds UNIQUEMENT à ce que la question demande, en 1 à 3 phrases "
            + "directes, sans fiche complète : si elle demande s'il est obligatoire, réponds oui ou non ; si elle "
            + "demande la valeur, donne la valeur documentée (« Valid values are », « Accepts value from »…). "
            + "Si la Description dit « Optional field », la réponse est NON, il n'est pas obligatoire. Rédige une "
            + "Si le champ n'apparaît que dans UNE table : UNE SEULE phrase complète qui intègre la valeur demandée. "
            + "Si le champ apparaît dans PLUSIEURS tables, donne une ligne par table (« - TABLE : règle ») car les règles "
            + "diffèrent d'une table à l'autre ; si une table n'a aucune règle documentée (« Help Text unavailable »), "
            + "écris « non documentée » pour elle. N'invente jamais un nombre, une longueur ou un format absent du "
            + "contexte. N'écris « Selon la documentation T24 » qu'une seule fois, sans conclusion ni politesse. Commence "
            + "directement par la réponse, sans reprise de la question ni citation du texte source. Ne liste "
            + "l'ensemble des règles de validation que si la question demande explicitement toutes les règles. Si une "
            + "information demandée n'est pas documentée, dis-le explicitement au lieu de la déduire. Sans conclusion "
            + "\"En résumé\".";

    private static final String COMPARE_REMINDER = "\n\nRappel : compare les éléments demandés à partir du contexte : "
            + "rôle de chacun, puis principales différences et points communs, en liste ou en tableau à deux "
            + "colonnes. N'affirme rien qui ne soit pas dans le contexte.";

    private static final String SIMPLE_REMINDER = "\n\nRappel : question fermée : réponds en 1 à 3 phrases directes "
            + "en français (oui ou non, puis la justification tirée de la Description), sans développement inutile.";

    private static final String MORE_REMINDER = "\n\nRappel : cette demande veut APPROFONDIR le sujet déjà discuté. "
            + "Apporte des informations NOUVELLES tirées du contexte (précisions, cas d'usage, dépendances, "
            + "conditions), sans répéter aucune phrase de tes réponses précédentes (voir l'historique) ; s'il n'y a "
            + "plus rien de nouveau dans le contexte, dis-le simplement. Développe (au moins 5 phrases ou une liste détaillée) ; réponds uniquement à cette demande, en "
            + "français. Commence directement par les informations nouvelles, sans rappel ni introduction générale." + NO_INVENTED_TURNS;

    private static final String EXAMPLE_REMINDER = "\n\nRappel : donne un EXEMPLE CONCRET lié au sujet déjà discuté, à "
            + "partir du contexte : introduis-le par \"Exemple issu de la documentation (champ X de la table Y) :\" "
            + "puis restitue fidèlement en français l'exemple qui figure dans la Description (mêmes nombres, mêmes "
            + "noms de champs, dans le même ordre ; n'ajoute ni personnage, ni chiffre, ni détail, et n'interprète "
            + "pas les codes au-delà de ce qui est écrit), avec une phrase d'explication de ce qu'il illustre. "
            + "Si le contexte ne contient aucun exemple, dis-le, puis propose un exemple "
            + "ILLUSTRATIF en le signalant explicitement (\"Exemple illustratif, non issu de la documentation\"). "
            + "Commence directement par cet exemple, sans rappel ni introduction générale sur le module. "
            + "Sans répéter tes réponses précédentes, en français." + NO_INVENTED_TURNS;

    private static final String VALUES_REMINDER = "\n\nRappel : indique les VALEURS possibles ou autorisées du sujet "
            + "déjà discuté, uniquement si le contexte les documente (valeurs listées, formats, plages) ; sinon dis "
            + "clairement qu'elles ne sont pas documentées. Sans répéter tes réponses précédentes, en français."
            + NO_INVENTED_TURNS;

    private static final String FOLLOWUP_VALIDATION_REMINDER = VALIDATION_REMINDER + " Cette question prolonge la "
            + "conversation : ne répète pas tes réponses précédentes ; si le contexte contient des règles propres à "
            + "plusieurs champs, présente-les champ par champ en précisant que ces règles sont spécifiques à "
            + "chaque champ. Commence par une phrase indiquant le champ et la table concernés, puis les règles, sans rappel du module." + NO_INVENTED_TURNS;

    /**
     * Consigne de rédaction ajoutée juste après la question (un petit modèle suit mieux une consigne placée en
     * dernier qu'une règle lointaine du prompt système) : longueur et structure adaptées au TYPE de question.
     */
    private static String reminderFor(String question, List<RetrieverService.ScoredDocument> context,
                                       String conversationHistory, QuestionIntent intent, boolean moduleMode) {
        if (BREVITY_REQUEST_PATTERN.matcher(question).find()) return "";
        if (intent != null) {
            switch (intent) {
                case FOLLOWUP_MORE -> { return MORE_REMINDER; }
                case FOLLOWUP_EXAMPLE -> { return EXAMPLE_REMINDER; }
                case FOLLOWUP_VALUES -> { return VALUES_REMINDER; }
                case FOLLOWUP_VALIDATION -> { return FOLLOWUP_VALIDATION_REMINDER; }
                case TOPIC_TABLES -> {
                    return SINGLE_TABLE_LOOKUP_PATTERN.matcher(question).find()
                            ? TABLE_LOOKUP_REMINDER : TOPIC_TABLES_REMINDER;
                }
                case COMPARE -> { return COMPARE_REMINDER; }
                case VALIDATION -> { return VALIDATION_REMINDER; }
                case SIMPLE -> { return SIMPLE_REMINDER; }
                default -> { }
            }
        }
        if (moduleMode) return MODULE_REMINDER;
        if (LIST_QUESTION_PATTERN.matcher(question).find()) return LIST_REMINDER;
        if (isAmbiguous(question, context, conversationHistory)) return AMBIGUOUS_REMINDER;
        if (isFollowUp(question, conversationHistory)) return FOLLOWUP_REMINDER + NO_INVENTED_TURNS;
        boolean tableLevel = !context.isEmpty()
                && Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(context.get(0).document().getColumnName());
        return tableLevel ? TABLE_REMINDER : FIELD_REMINDER;
    }

    /**
     * Le petit modèle local prolonge parfois la conversation lui-même en écrivant une nouvelle ligne
     * "Question : ..." / "Utilisateur : ..." (et sa propre réponse) à la place de l'utilisateur : tout ce qui suit
     * une telle ligne est coupé.
     */
    private static final Pattern INVENTED_TURN_PATTERN = Pattern.compile("(?im)^\\s*(?:question|utilisateur|user|vous)\\s*:.*$", Pattern.DOTALL);

    static String cutInventedTurns(String answer) {
        if (answer == null) return null;
        Matcher m = INVENTED_TURN_PATTERN.matcher(answer);
        if (!m.find()) return answer;
        String kept = answer.substring(0, m.start()).trim();
        return kept.isEmpty() ? answer : kept;
    }

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;:])\\s+");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+.*");
    private static final int LOOP_PREFIX_WORDS = 5;
    private static final int MAX_SAME_PREFIX_SENTENCES = 2;

    private static String normalizeForRepeat(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * Retire les boucles de répétition du petit modèle local : lignes strictement identiques, phrases de prose
     * dont le début (5 mots) revient plus de deux fois, et phrase finale tronquée (réponse coupée par la limite
     * de tokens en pleine phrase) quand il reste du contenu avant elle.
     */
    static String removeRepetitions(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        java.util.Set<String> seenLines = new java.util.HashSet<>();
        java.util.Map<String, Integer> prefixCount = new java.util.HashMap<>();
        StringBuilder out = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            String norm = normalizeForRepeat(line);
            if (norm.length() >= 20 && !seenLines.add(norm)) continue;
            if (LIST_LINE.matcher(line).matches() || line.startsWith("|") || line.isBlank()) {
                out.append(line).append('\n');
                continue;
            }
            StringBuilder kept = new StringBuilder();
            for (String sentence : SENTENCE_SPLIT.split(line)) {
                String[] words = normalizeForRepeat(sentence).split(" ");
                if (words.length >= LOOP_PREFIX_WORDS) {
                    String prefix = String.join(" ", java.util.Arrays.copyOf(words, LOOP_PREFIX_WORDS));
                    if (prefixCount.merge(prefix, 1, Integer::sum) > MAX_SAME_PREFIX_SENTENCES) continue;
                }
                if (kept.length() > 0) kept.append(' ');
                kept.append(sentence);
            }
            if (kept.length() > 0) out.append(kept).append('\n');
        }
        String result = out.toString().stripTrailing();
        // Phrase finale tronquée : coupée au dernier signe de fin de phrase s'il y a assez de contenu avant.
        // Uniquement pour une dernière ligne de prose (jamais une puce ou une ligne de tableau, qui peut légitimement
        // se terminer sans ponctuation).
        int lastNl = result.lastIndexOf('\n');
        String lastLine = result.substring(lastNl + 1);
        if (!lastLine.isBlank() && !LIST_LINE.matcher(lastLine).matches() && !lastLine.startsWith("|")
                && !lastLine.matches("(?s).*[.!?:)\"»]\\s*$")) {
            int cut = Math.max(lastLine.lastIndexOf(". "), Math.max(lastLine.lastIndexOf('!'), lastLine.lastIndexOf('?')));
            if (cut > 0) result = result.substring(0, lastNl + 1 + cut + 1);
            else if (lastNl > 0) result = result.substring(0, lastNl).stripTrailing();
        }
        return result;
    }

    /** Vrai si l'historique contient de vrais échanges (et pas seulement le rappel du libellé du module, qui
     *  rend {@code conversationHistory} non vide dès qu'un module est sélectionné). */
    private static boolean hasPriorTurns(String conversationHistory) {
        return conversationHistory != null && (conversationHistory.contains("Utilisateur :") || conversationHistory.contains("Assistant :"));
    }

    /** Question courte posée au sein d'une conversation déjà entamée ("et il est obligatoire ?"). */
    private static boolean isFollowUp(String question, String conversationHistory) {
        return hasPriorTurns(conversationHistory) && question.trim().split("\\s+").length <= 9;
    }

    /** Question d'existence ("y a-t-il un champ X ?") : voir generate. */
    private static final Pattern EXISTENCE_QUESTION_PATTERN = Pattern.compile(
            "(?iu)\\b(il y a (?:un |des )?(?:champ|table)|y a-t-il|existe-t-il|existe (?:un|une|des))");

    /** Phrase constatant l'absence d'un élément : voir generate. */
    private static final Pattern NEGATIVE_SENTENCE_PATTERN = Pattern.compile(
            "(?iu)[^.\\n]*(?:il n'y a (?:pas|aucun)|n'existe pas|aucun (?:champ|[ée]l[ée]ment|table))[^.\\n]*\\.");

    /** Question fermée (oui/non, caractère obligatoire) : une réponse courte y est légitime. */
    private static final Pattern SHORT_ANSWER_QUESTION_PATTERN = Pattern.compile(
            "(?iu)^\\s*(?:et\\s+)?(?:est-ce|est-il|est-elle|y a-t-il|existe)\\b|\\bobligatoire\\b|\\boptionnel\\b");

    /** Nombre de mots minimal attendu quand le contexte contient une vraie Description développée. */
    private static final int MIN_WORDS_RICH_CONTEXT = 45;

    private static boolean isTooTerse(String question, String answer, List<RetrieverService.ScoredDocument> context) {
        if (question == null || answer == null) return false;
        if (BREVITY_REQUEST_PATTERN.matcher(question).find()
                || SHORT_ANSWER_QUESTION_PATTERN.matcher(question).find()
                || NEGATIVE_FINDING_PATTERN.matcher(answer).find()) return false;
        int words = answer.trim().split("\\s+").length;
        if (NAMING_QUESTION_PATTERN.matcher(question).find() && words < MIN_SUBSTANTIVE_WORDS) return true;
        boolean richContext = context.stream()
                .anyMatch(c -> c.document().getContent().trim().split("\\s+").length >= 45);
        return richContext && words < MIN_WORDS_RICH_CONTEXT;
    }

    /**
     * Mots/expressions anglais qui n'apparaissent normalement jamais dans une réponse conforme à la règle 7 du
     * system prompt (réponse intégralement en français) : détecte le cas observé en pratique où le petit modèle
     * local (llama3.2:3b), malgré un contexte pourtant correct et complet, répond par un refus ou une excuse
     * entièrement en anglais (ex. "I'm sorry, but without more information, I can't tell you exactly which table
     * the "champ" refers to."). Ce genre de réponse ne cite aucun identifiant précis comme sujet, donc {@link
     * #citesOnlyContextIdentifiers} ne la rejette pas : cette vérification distincte comble ce trou. Liste
     * volontairement limitée à des tournures anglaises sans équivalent français plausible (jamais un simple mot
     * emprunté), pour ne jamais rejeter à tort une vraie réponse française qui citerait un terme T24 en anglais.
     */
    private static final Pattern ENGLISH_LEAKAGE_PATTERN = Pattern.compile(
            "(?iu)\\b(i'm sorry|i am sorry|i cannot|i can't|i don't have|i do not have|please provide|"
                    + "unfortunately|as an ai|i'm not sure|i am not sure|refers to|without more information|"
                    // Marqueurs anglais jamais légitimes en français, même comme emprunt courant (contrairement à
                    // "workflow"/"process", empruntés en français technique — volontairement exclus d'ici) : un
                    // seul suffit, contrairement à ENGLISH_PROSE_PATTERN ci-dessous (mots plus ambigus, ex.
                    // "the"/"of"/"is", qui exigent 2 occurrences). Observé en pratique : "This Process Workflow
                    // aide l'utilisateur..." — une seule occurrence de "this", jamais rejetée avant ce correctif
                    // car ENGLISH_PROSE_MIN_MATCHES exigeait une seconde occurrence anglaise ambiguë.
                    + "this|these|whether|shall)\\b");

    /**
     * Mots anglais très courants dans le HelpText T24 source (descriptions techniques copiées telles quelles au
     * lieu d'être traduites, en violation de la règle 10) mais qui n'apparaissent normalement jamais dans une \
     * vraie phrase française — contrairement à {@link #ENGLISH_LEAKAGE_PATTERN} (phrases de refus complètes, 1 \
     * occurrence suffit), ces mots isolés peuvent en théorie apparaître une fois par accident (nom propre, sigle) \
     * donc on exige au moins {@link #ENGLISH_PROSE_MIN_MATCHES} occurrences avant de conclure à une description \
     * copiée en anglais plutôt que traduite (observé en pratique : liste de champs dont chaque description est \
     * la phrase HelpText anglaise intacte, ex. "Specifies the customer who owns the account. Validation Rules: \
     * ...", jamais rejetée car aucune phrase de refus n'y figure).
     */
    private static final Pattern ENGLISH_PROSE_PATTERN = Pattern.compile(
            // "of" volontairement exclu : trop souvent un faux positif au sein d'un libellé officiel de module
            // légitimement conservé en anglais (ex. "Letters of Credit", le libellé officiel du module LC),
            // jamais une preuve fiable de fuite anglaise à lui seul.
            "(?iu)\\b(the|is|are|this|these|must|shall|specifies|whether|validation rules|mandatory|"
                    + "optional input|identifies|indicates)\\b");

    private static final int ENGLISH_PROSE_MIN_MATCHES = 2;

    static boolean looksLikeEnglish(String answer) {
        if (answer == null) return false;
        if (ENGLISH_LEAKAGE_PATTERN.matcher(answer).find()) return true;
        Matcher m = ENGLISH_PROSE_PATTERN.matcher(answer);
        int count = 0;
        while (m.find()) if (++count >= ENGLISH_PROSE_MIN_MATCHES) return true;
        return false;
    }

    /**
     * Toute formule de sourçage ("Selon...", "D'après..."), pas seulement la formule canonique exacte —
     * volontairement large pour ne jamais préfixer en double par-dessus une variante déjà présente (observé en
     * pratique : "Selon la documentation T24, Selon les informations fournies, ..." quand le LLM utilisait sa
     * propre formule, différente de celle attendue mais déjà équivalente). Voir {@link #ensureGroundedOpening}.
     */
    private static final Pattern GROUNDED_OPENER_PATTERN = Pattern.compile("(?iu)\\b(selon|d['’]après)\\b");

    /** Fenêtre de recherche (en caractères, depuis le début de la réponse) de {@link #GROUNDED_OPENER_PATTERN} :
     *  couvre le cas où le LLM fait précéder sa formule de sourçage d'une courte phrase de changement de sujet
     *  (règle 6 du system prompt, ex. "D'accord, on passe maintenant au module X, Selon la documentation T24,
     *  ...") — sans cette fenêtre, {@link #ensureGroundedOpening} ne voyait la formule qu'en toute première
     *  position et en ajoutait une seconde en tête, produisant un doublon visible ("Selon la documentation T24,
     *  D'accord, on passe... Selon la documentation T24, ..."). Reste assez courte pour ne jamais confondre avec
     *  une occurrence tardive et non intentionnelle du mot "selon" au sens ordinaire, ailleurs dans une longue
     *  réponse. */
    private static final int GROUNDED_OPENER_SEARCH_WINDOW = 160;

    /**
     * Garantit que la réponse commence bien par une formule indiquant qu'elle vient de la documentation T24,
     * sans dépendre du LLM pour respecter cette consigne à chaque fois (observé en pratique : réponse commençant
     * directement par "Cette table semble contenir...", sans aucune formule de sourçage, alors que le contexte
     * était pourtant réel). Si aucune formule de ce type n'est déjà présente en tête (ou juste après une phrase
     * de changement de sujet, voir {@link #GROUNDED_OPENER_SEARCH_WINDOW}), en préfixe une par défaut, sans
     * toucher au reste du texte.
     */
    private static String ensureGroundedOpening(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        String trimmed = answer.trim();
        String window = trimmed.substring(0, Math.min(GROUNDED_OPENER_SEARCH_WINDOW, trimmed.length()));
        if (GROUNDED_OPENER_PATTERN.matcher(window).find()) {
            // Formule suivie d'un saut de ligne ("Selon la documentation T24,\n\nLe module ...") : on recolle.
            String joined = trimmed.replaceFirst("^((?:Selon|D'après) la (?:documentation|doc) T24,)\\s*\\n+\\s*(?=[\\p{L}])", "$1 ");
            // "Selon la documentation T24, Le module" -> "..., le module" (majuscule d'un article après la virgule).
            return Pattern.compile("^((?:Selon|D'après) la (?:documentation|doc) T24, )(Le|La|Les|Ce|Cette|Un|Une) ")
                    .matcher(joined)
                    .replaceFirst(m -> m.group(1) + m.group(2).toLowerCase() + " ");
        }
        // Devant un titre, une liste ou un tableau, la formule reste sur sa propre ligne.
        boolean block = trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.startsWith("|");
        return block ? "Selon la documentation T24 :\n\n" + trimmed : "Selon la documentation T24, " + trimmed;
    }

    /** Ollama a arrêté la génération à la limite de tokens ({@code done_reason = "length"}) : la dernière ligne est
     *  probablement coupée en plein mot, elle est retirée. */
    static String dropCutOffLastLine(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        String s = answer.stripTrailing();
        int nl = s.lastIndexOf('\n');
        String last = s.substring(nl + 1);
        if (nl > 0 && !last.matches("(?s).*[.!?)\"»|]\\s*$")) return s.substring(0, nl).stripTrailing();
        return s;
    }

    /**
     * Retire les puces "* " en tête de phrase ou de segment (ex: "...etc. * Les conditions de paiement...") que
     * le LLM utilise parfois malgré l'absence de consigne en ce sens — l'interface n'est pas un rendu Markdown,
     * ces astérisques s'affichent tels quels et ne sont jamais voulus dans une réponse de chat.
     */
    private static String stripBulletAsterisks(String answer) {
        if (answer == null) return null;
        // L'interface rend maintenant un Markdown simple (titres, gras, listes, tableaux) : le gras "**mot**" est
        // conservé, et une puce "* " devient une puce "- " (une seule forme de liste, voir formatDashListOnNewLines).
        return answer.replaceAll("(?m)^(\\s*)\\*\\s+", "$1- ").replaceAll("(?<=\\s)\\*\\s+(?=\\S)", "- ");
    }

    /** Une rubrique "## Titre" qui, plutôt que d'être omise (comme demandé par {@link #MODULE_REMINDER} quand
     *  aucune table du contexte n'en relève), est conservée avec une seule phrase constatant qu'elle est vide. */
    private static final Pattern EMPTY_SECTION_FILLER_PATTERN = Pattern.compile(
            "(?iu)n'est pas applicable|non applicable|aucune table|pas de table|ne traite pas|"
                    + "pas mentionn[ée]e?|pas document[ée]e?|sans (objet|rapport)");

    private static final Pattern MARKDOWN_HEADING_LINE = Pattern.compile("(?m)^(#{2,4})\\s+.*$");

    /**
     * Retire les rubriques "## Titre" que le petit modèle local garde malgré la consigne de les omettre quand
     * elles ne correspondent à aucune table du contexte (observé en pratique : "## Configuration et Paramétrage"
     * suivi de la seule phrase "Aucune table n'est spécifiquement mentionnée dans le contexte pour ce module.",
     * au lieu de simplement ne pas écrire cette rubrique). Ne retire QUE les rubriques sans aucune puce à tirets
     * ET contenant une des formules constatant l'absence — jamais une rubrique avec du vrai contenu.
     */
    static String stripEmptyModuleSections(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        Matcher headings = MARKDOWN_HEADING_LINE.matcher(answer);
        List<int[]> spans = new ArrayList<>();
        int prevStart = -1;
        while (headings.find()) {
            if (prevStart >= 0) spans.add(new int[]{prevStart, headings.start()});
            prevStart = headings.start();
        }
        if (prevStart >= 0) spans.add(new int[]{prevStart, answer.length()});
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (int[] span : spans) {
            String block = answer.substring(span[0], span[1]);
            // Un titre sans aucun saut de ligne après lui (dernier titre du texte, sans puce ni contenu à la
            // suite) n'a par définition AUCUN corps : sans ce cas particulier, indexOf('\n') renvoyait -1 et
            // substring(0) capturait à tort le titre lui-même comme "corps", jamais reconnu comme vide.
            String body = block.contains("\n") ? block.substring(block.indexOf('\n') + 1) : "";
            boolean hasBullet = body.lines().anyMatch(l -> l.stripLeading().startsWith("-"));
            // Vide au sens strict (rien après le titre), ou seulement une phrase constatant l'absence : dans les
            // deux cas, rien à montrer sous ce titre.
            boolean isEmptyFiller = !hasBullet
                    && (body.isBlank() || EMPTY_SECTION_FILLER_PATTERN.matcher(body).find());
            if (isEmptyFiller) {
                out.append(answer, last, span[0]);
                last = span[1];
            }
        }
        out.append(answer, last, answer.length());
        return out.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    /**
     * Formules creuses/génériques que {@link #MODULE_REMINDER} interdit explicitement mais que le petit modèle
     * local réutilise parfois quand même (observé en pratique : "Il s'agit d'un acteur incontournable pour la
     * gestion des flux de travail...", malgré l'interdiction) — filet de sécurité déterministe qui retire toute
     * la PHRASE contenant une de ces formules (jamais un fragment isolé, qui laisserait une phrase brisée).
     */
    private static final Pattern GENERIC_FILLER_SENTENCE = Pattern.compile(
            "(?iu)(?:(?<=[.!?]\\s)|^)[^.!?\\n]{0,25}\\b(acteur incontournable|composant (?:cl[ée]|essentiel)|"
                    + "solution compl[eè]te|large gamme de (?:fonctionnalit[ée]s|services)|"
                    + "services modernes et s[ée]curis[ée]s|joue un r[ôo]le (?:crucial|essentiel)|"
                    // Conclusion auto-référentielle sur la fiabilité/précision de la réponse elle-même (règle 6/20
                    // du system prompt) : n'apporte aucune information, jamais une affirmation à prendre pour
                    // argent comptant venant du modèle qui l'a lui-même générée.
                    + "cette r[ée]ponse est (?:pr[ée]cise|fiable|exacte|compl[eè]te)|"
                    + "cette information est (?:pr[ée]cise|fiable|exacte)|"
                    + "bas[ée]e? sur (?:la documentation|les sources) officielle|"
                    // Narration du modèle sur sa propre démarche (règles internes, consignes) : jamais un vrai
                    // contenu de réponse, observé en pratique sur une question de suivi courte ("c quoi ce module").
                    + "je vais (?:r[ée]pondre|maintenant r[ée]pondre)|"
                    + "(?:selon|en fonction des?) les? r[èe]gles (?:que nous avons )?[ée]tablies)"
                    + "[^.!?\\n]*[.!?]\\s*");

    /** Caractère obligatoire/facultatif ou valeur par défaut d'un champ, cité alors que la question ne le demande
     *  pas : {@link #FIELD_REMINDER} l'interdit explicitement (« réponds sur son rôle et son fonctionnement »,
     *  rien d'autre sauf demande explicite), mais le petit modèle local le fait parfois quand même — jusqu'à
     *  inventer une absence de valeur par défaut non mentionnée dans la documentation (observé en pratique, champ
     *  CURRENCY de AC.ACC.OPENING : la doc réelle ne dit RIEN sur une valeur par défaut, et l'utilisateur n'avait
     *  pas demandé si le champ est obligatoire). */
    private static final Pattern UNREQUESTED_MANDATORY_CLAIM = Pattern.compile(
            "(?iu)(?:(?<=[.!?]\\s)|^)[^.!?\\n]*\\b(obligatoire|facultatif|optionnel|"
                    + "valeur par d[ée]faut|par d[ée]faut)\\b[^.!?\\n]*[.!?]\\s*");

    /** Retire les phrases visées par {@link #UNREQUESTED_MANDATORY_CLAIM}, sauf si {@code question} demande
     *  elle-même explicitement le caractère obligatoire/facultatif du champ (même motif que {@link
     *  #MANDATORY_TERMS_PATTERN}, utilisé par {@link #perTableValidationAnswer}) — dans ce cas, la réponse peut
     *  légitimement en parler. Ne retire jamais tout le texte (filet de sécurité : réponse originale conservée
     *  si le résultat serait vide). */
    static String dropUnrequestedMandatoryClaims(String text, String question) {
        if (text == null || text.isBlank() || question == null) return text;
        if (MANDATORY_TERMS_PATTERN.matcher(question).find()) return text;
        String stripped = UNREQUESTED_MANDATORY_CLAIM.matcher(text).replaceAll("").strip();
        return stripped.isBlank() ? text : stripped;
    }

    /** Retire toute phrase contenant une formule creuse interdite (voir {@link #GENERIC_FILLER_SENTENCE}), sans
     *  jamais laisser de phrase incomplète ni casser le reste du texte. */
    private static final Pattern PRIOR_QUESTION_PREAMBLE = Pattern.compile(
            "(?iu)\\s*pour r[ée]pondre [àa] votre question pr[ée]c[ée]dente\\s*:[^\\n]*");

    /** Reprise parasite de la question précédente (« Pour répondre à votre question précédente : c'est aux valeurs
     *  acceptées… »), en début OU en milieu de réponse, jusqu'à la fin de sa ligne : formule inutile, retirée
     *  déterministement puisque le modèle la répète même quand la consigne l'interdit. */
    /** Réponse déterministe pour « quelles sont les règles de validation d'un champ », qu'il existe dans une seule
     *  table (une phrase directe) ou plusieurs (une ligne par table) : la règle est prise telle quelle dans la
     *  documentation retrouvée ; absente/« non documentée » si la table n'a pas de règle écrite. Jamais de LLM, à
     *  une ou plusieurs tables : il invente des règles absentes, les mélange d'une table à l'autre (observé en
     *  pratique, TRANSACTION.REF), ou pire, à une seule table, ignore la consigne et répond par la DÉFINITION du
     *  champ au lieu de sa règle de validation pourtant présente dans la documentation (observé en pratique,
     *  BEN.CUSTNO de LC.BENEFICIARY). Texte de la règle traduit en français (voir {@link #translateToFrench}) :
     *  la documentation HelpText source est en anglais, et une réponse "en français" ne doit pas se terminer par
     *  une phrase recopiée telle quelle de l'anglais (observé en pratique, demande explicite de l'utilisateur). */
    /** Termes qui demandent précisément le caractère obligatoire d'un champ — distinct de {@link #VALIDATION_DETAIL_PATTERN}
     *  (format, longueur, valeurs, règles) : voir {@link #perTableValidationAnswer}. */
    private static final Pattern MANDATORY_TERMS_PATTERN = Pattern.compile("(?iu)\\b(obligatoire|facultatif|optionnel)\\b");

    /** Termes qui demandent le détail d'une règle de validation (format, longueur, valeurs, contrôle de saisie) —
     *  distinct de {@link #MANDATORY_TERMS_PATTERN} : voir {@link #perTableValidationAnswer}. */
    private static final Pattern VALIDATION_DETAIL_PATTERN = Pattern.compile(
            "(?iu)r[èe]gles?\\s+de\\s+(validation|saisie|contr[ôo]le)|contr[ôo]les?\\s+de\\s+saisie|\\bvalidation\\b|"
                    + "valeurs?\\s+(autoris[ée]es?|possibles?|accept[ée]es?|permises?)|formats?\\b|longueurs?\\b|"
                    + "combien de caract[èe]res");

    private String perTableValidationAnswer(String question, List<RetrieverService.ScoredDocument> context) {
        List<Document> fields = context.stream().map(RetrieverService.ScoredDocument::document)
                .filter(d -> !Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName())
                        && !Document.MODULE_SUMMARY_COLUMN_MARKER.equals(d.getColumnName()))
                .toList();
        if (fields.isEmpty()) return null;
        String field = fields.get(0).getColumnName();
        // "obligatoire"/"facultatif"/"optionnel" fait partie des termes qui classent déjà la question en VALIDATION
        // (voir QuestionIntent), mais une question qui ne contient QUE ces termes ("est-il obligatoire ?") demande
        // une réponse OUI/NON précise, jamais le texte brut de la règle de validation en plus (bug trouvé en
        // pratique, champ ADDRESS de DE.CARRIER : « je veux que la réponse soit précise... pas d'info inutile »,
        // alors que la réponse citait toute la règle avant de répondre). Inversement, une question sur le détail
        // de la règle ("contrôle de saisie", "règles de validation"...) ne demande que la règle, jamais le
        // caractère obligatoire en plus (déjà établi, cas BEN.CUSTNO plus tôt).
        boolean askedMandatory = MANDATORY_TERMS_PATTERN.matcher(question).find();
        boolean askedRuleDetail = VALIDATION_DETAIL_PATTERN.matcher(question).find();
        Map<String, String> byTable = new LinkedHashMap<>();
        for (Document d : fields) {
            String rule = validationRuleOf(d.getContent());
            byTable.putIfAbsent(d.getTableName(), rule == null ? null : translateToFrench(rule));
        }
        if (byTable.size() == 1) {
            if (askedMandatory && !askedRuleDetail) {
                String mandatory = MandatoryClassifier.classify(
                        com.biat.t24.assistant.controller.ChatController.extractDescription(fields.get(0).getContent()));
                return switch (mandatory) {
                    case "OUI" -> "Selon la documentation T24, le champ " + field + " est obligatoire.";
                    case "NON" -> "Selon la documentation T24, le champ " + field + " n'est pas obligatoire (optionnel).";
                    case "NON (auto-calculé)" -> "Selon la documentation T24, le champ " + field + " n'est pas à "
                            + "saisir : il est calculé automatiquement par le système.";
                    default -> "Selon la documentation T24, le caractère obligatoire du champ " + field
                            + " n'est pas précisé dans la documentation.";
                };
            }
            String rule = byTable.values().iterator().next();
            String base = rule == null
                    ? "Selon la documentation T24, aucune règle de validation n'est documentée pour le champ " + field + "."
                    : "Selon la documentation T24, le champ " + field + " a comme règle de validation : " + rule;
            if (!askedMandatory) return base;
            String mandatory = MandatoryClassifier.classify(
                    com.biat.t24.assistant.controller.ChatController.extractDescription(fields.get(0).getContent()));
            String mandatoryPhrase = switch (mandatory) {
                case "OUI" -> "Ce champ est obligatoire.";
                case "NON" -> "Ce champ n'est pas obligatoire (optionnel).";
                case "NON (auto-calculé)" -> "Ce champ est calculé automatiquement par le système (aucune saisie n'est requise).";
                default -> null;
            };
            return mandatoryPhrase == null ? base : base + (base.endsWith(".") ? " " : ". ") + mandatoryPhrase;
        }
        StringBuilder out = new StringBuilder("Selon la documentation T24, le champ " + field
                + " a des règles de validation qui dépendent de la table :\n");
        byTable.forEach((table, rule) -> out.append("- ").append(table).append(" : ")
                .append(rule == null ? "non documentée" : rule).append("\n"));
        return out.toString().strip();
    }

    private static String validationRuleOf(String content) {
        if (content == null) return null;
        String lower = content.toLowerCase();
        if (lower.contains("help text for this field is unavailable")) return null;
        int i = lower.indexOf("validation rules");
        if (i < 0) return null;
        String rule = content.substring(i + "validation rules".length()).replaceFirst("^[\\s:]+", "")
                .replaceAll("\\s+", " ").strip();
        if (rule.isEmpty()) return null;
        return rule.length() > 240 ? rule.substring(0, 240).strip() : rule;
    }

    private static final Pattern TABLE_SEPARATOR_LINE = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    /** Tableau Markdown sans ligne d'en-tête (la ligne « | --- | --- | » arrive en premier) : le rendu l'affiche en
     *  barres verticales brutes. Ses lignes deviennent des puces « - première cellule : autres cellules ». */
    static String fixHeaderlessTables(String text) {
        if (text == null || !text.contains("|")) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            boolean orphanSeparator = TABLE_SEPARATOR_LINE.matcher(lines[i]).matches()
                    && (i == 0 || !lines[i - 1].strip().startsWith("|"));
            if (!orphanSeparator) {
                out.append(lines[i]).append("\n");
                continue;
            }
            int j = i + 1;
            while (j < lines.length && lines[j].strip().startsWith("|")) {
                List<String> cells = java.util.Arrays.stream(lines[j].strip().split("\\|"))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
                if (!cells.isEmpty()) {
                    out.append("- ").append(cells.get(0));
                    if (cells.size() > 1) out.append(" : ").append(String.join(" ; ", cells.subList(1, cells.size())));
                    out.append("\n");
                }
                j++;
            }
            i = j - 1;
        }
        return out.toString().stripTrailing();
    }

    /** Notions que le modèle recopie parfois de son savoir général (« contrats non consolidés », « amendes », « fichiers ») :
     *  une phrase qui les contient est retirée si la documentation fournie ne les mentionne pas. */
    private static final List<String> UNSUPPORTED_TERMS = List.of("consolid", "amende", "fichier", "dossier");

    static String dropUnsupportedSentences(String text, String sourceText) {
        if (text == null) return null;
        String source = sourceText == null ? "" : sourceText.toLowerCase();
        return java.util.Arrays.stream(text.strip().split("(?<=[.!?])\\s+"))
                .filter(s -> UNSUPPORTED_TERMS.stream()
                        .noneMatch(t -> s.toLowerCase().contains(t) && !source.contains(t)))
                .collect(Collectors.joining(" "));
    }

    /** Retire les phrases de l'introduction qui citent un nom technique (ex. MD.DEAL) : la présentation générale doit
     *  rester en langage courant, sans rejeter pour autant toute la description. */
    static String dropSentencesWithTechnicalNames(String text) {
        if (text == null) return null;
        return java.util.Arrays.stream(text.strip().split("(?<=[.!?])\\s+"))
                .filter(s -> !UPPER_DOTTED_TOKEN.matcher(s).find())
                .collect(Collectors.joining(" "));
    }

    /** Lignes de rubriques : une puce citant une notion absente de la documentation est retirée, et chaque puce
     *  ne garde que sa première phrase (réponse courte et fidèle). Les titres et le reste sont conservés. */
    static String dropUnsupportedLines(String rubrics, String sourceText) {
        if (rubrics == null || rubrics.isBlank()) return rubrics;
        String source = sourceText == null ? "" : sourceText.toLowerCase();
        StringBuilder out = new StringBuilder();
        for (String line : rubrics.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("- ")) {
                String body = firstSentenceOnly(stripped);
                boolean unsupported = UNSUPPORTED_TERMS.stream()
                        .anyMatch(t -> body.toLowerCase().contains(t) && !source.contains(t));
                if (unsupported) continue;
                out.append(body).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return stripEmptyModuleSections(out.toString().stripTrailing());
    }

    private static final Pattern SUMMARY_PARAGRAPH =Pattern.compile("(?iu)\\s*En (?:r[ée]sum[ée]|conclusion)[^\\n]*");

    /** Paragraphe de conclusion « En résumé… » retiré sans condition : il ne fait que répéter l'introduction. */
    static String stripSummaryParagraph(String text) {
        if (text == null) return null;
        return SUMMARY_PARAGRAPH.matcher(text).replaceAll("").strip();
    }

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+|\\n");

    /** Ne garde que la première phrase : une question de propriété précise (valeur, obligatoire…) appelle une seule
     *  réponse directe. Un point suivi d'un espace uniquement, donc « SLEEP.LOCK.TIME » n'est jamais coupé. */
    static String firstSentenceOnly(String text) {
        if (text == null) return null;
        String t = text.strip();
        Matcher m = SENTENCE_BREAK.matcher(t);
        return m.find() ? t.substring(0, m.start()).strip() : t;
    }

    private static final Pattern CLOSING_INVITATION = Pattern.compile(
            "(?iu)\\s*N['’]h[ée]sitez pas [^\\n]*");

    /** Formule de politesse de fin (« N'hésitez pas à me demander… ») retirée déterministement : le modèle l'ajoute
     *  malgré la consigne, et une question de champ ou de propriété se termine dès la réponse donnée. */
    static String stripClosingInvitation(String answer) {
        if (answer == null) return null;
        return CLOSING_INVITATION.matcher(answer).replaceAll("").strip();
    }

    static String stripPriorQuestionPreamble(String answer) {
        if (answer == null) return null;
        return PRIOR_QUESTION_PREAMBLE.matcher(answer).replaceAll("").strip();
    }

    static String stripGenericFillerSentences(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        return GENERIC_FILLER_SENTENCE.matcher(answer).replaceAll("").replaceAll("[ \\t]{2,}", " ").strip();
    }

    /**
     * Phrase d'introduction d'une réponse MODULE_GENERAL construite DÉTERMINISTIQUEMENT (jamais par le LLM) à
     * partir des vraies données du contexte : libellé officiel, nombre de tables documentées, et 2-3 exemples
     * de tables réellement présentes dans le contexte. Corrige à la racine le problème récurrent d'introduction
     * creuse ou circulaire ("le module Electronic Banking gère les opérations d'Electronic Banking") : en ne
     * laissant plus le petit modèle local rédiger lui-même cette phrase, elle est TOUJOURS fondée sur des faits
     * vérifiables (jamais une reformulation de son propre nom anglais). Chaîne vide si le document de synthèse
     * du module (voir {@link Document#MODULE_SUMMARY_COLUMN_MARKER}) est absent du contexte.
     */
    static String moduleIntroSentence(List<RetrieverService.ScoredDocument> context) {
        Document summary = context.stream()
                .filter(c -> Document.MODULE_SUMMARY_COLUMN_MARKER.equals(c.document().getColumnName()))
                .map(RetrieverService.ScoredDocument::document).findFirst().orElse(null);
        if (summary == null) return "";
        String content = summary.getContent();
        String code = fieldFromSummary(content, "Module : ");
        String label = fieldFromSummary(content, "Libellé officiel du module : ");
        String count = fieldFromSummary(content, "Nombre de tables documentées : ");
        List<String> examples = context.stream()
                .filter(c -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(c.document().getColumnName()))
                .map(c -> c.document().getTableName())
                .distinct().limit(3).toList();

        StringBuilder sb = new StringBuilder("Selon la documentation T24, le module ").append(code == null ? "" : code);
        if (label != null && !label.isBlank()) sb.append(" (").append(label).append(')');
        sb.append(" regroupe ");
        sb.append(count != null && !count.isBlank() ? count + " tables documentées" : "plusieurs tables documentées");
        if (!examples.isEmpty()) {
            sb.append(", dont notamment ");
            sb.append(examples.size() == 1 ? examples.get(0)
                    : String.join(", ", examples.subList(0, examples.size() - 1)) + " et " + examples.get(examples.size() - 1));
        }
        sb.append(", réparties ci-dessous par grande famille d'utilisation :");
        return sb.toString();
    }

    /**
     * Fait de rattachement déterministe et TOUJOURS correct (code + libellé officiel réel, jamais deviné à partir
     * du sens ordinaire de ce libellé en anglais) — contrairement à {@link #moduleIntroSentence}, ne mentionne
     * JAMAIS le nombre de tables : un inventaire n'est pas une définition, et le LLM continue la présentation
     * immédiatement après avec une véritable explication fonctionnelle (voir {@link #extractLlmModuleIntro}). Vide
     * si le document de synthèse du module est absent du contexte.
     */
    /**
     * Prompt système DÉDIÉ à la seule présentation générale d'un module (appel LLM séparé de celui qui rédige
     * les rubriques, voir {@link #moduleSynthesisIntro}) : une tâche unique et ciblée, bien plus fiable pour un
     * petit modèle local qu'un seul prompt combinant à la fois une synthèse rédactionnelle ET une structuration
     * en rubriques Markdown — observé en pratique, cette double charge faisait parfois disparaître l'une des
     * deux (le modèle oubliait d'écrire une introduction, ou oubliait les titres "## ").
     */
    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            Tu es un expert Temenos T24. On te donne les vues d'ensemble de plusieurs tables d'un même module. \
            Ta seule tâche : rédiger en français naturel et professionnel, en 3 à 6 phrases, une présentation \
            générale de ce que ce module représente et de son rôle, compréhensible par quelqu'un qui le \
            découvre pour la première fois. Cherche ce que ces tables indiquent COLLECTIVEMENT sur le rôle du \
            module — regroupe les informations qui se recoupent (mêmes domaines, mêmes types d'opérations) au \
            lieu de les énumérer une par une table par table : à quels domaines fonctionnels ou techniques \
            elles se rattachent, comment elles s'articulent entre elles. N'affirme rien qui ne soit pas appuyé \
            par ce texte ; ne déduis JAMAIS le rôle du module à partir du sens habituel de son nom anglais, ni \
            du seul fait qu'un nom de table commence par le code du module. Si ces tables sont vraiment trop \
            disparates pour en tirer une compréhension cohérente, dis-le en une phrase plutôt que d'inventer un \
            lien qui n'existe pas — mais ne conclus PAS à cette absence trop vite : si plusieurs tables \
            pointent raisonnablement vers une même idée, formule cette synthèse. Ne cite aucun nom de fichier \
            ni de champ technique dans ce texte (ce niveau de détail est donné séparément après) : uniquement \
            une explication en langage courant. Reprends le vocabulaire EXACT de la documentation (par exemple « hors \
            bilan », « contingent ») et n'introduis aucune notion absente du texte fourni (pas d'« amende », de « fichier » \
            ni de « contrat non consolidé » si le texte ne les mentionne pas). Réponds uniquement par ce texte, sans \
            titre, sans liste à puces, sans guillemets, sans conclusion (pas de paragraphe « En résumé »), sans formule \
            de politesse ni du type "N'hésitez pas".""";

    private static final int SYNTHESIS_MAX_TABLES = 6;
    private static final int SYNTHESIS_DOC_CHARS = 700;
    // Température codée en dur (identique à ANSWER_TEMPERATURE) plutôt que référencée : ce champ est déclaré plus
    // loin dans la classe, et Java interdit la référence anticipée d'un champ statique par son nom simple dans
    // l'initialiseur d'un autre champ statique déclaré avant lui.
    private static final Map<String, Object> SYNTHESIS_OPTIONS =
            Map.of("num_predict", 220, "temperature", 0.2, "num_ctx", 4096);

    /** Nombre de tentatives avant de céder à {@link #MODULE_INTRO_FALLBACK} (voir {@link #moduleSynthesisIntro}) :
     *  3 plutôt que 2 — observé en pratique, un module dont les vues d'ensemble disponibles sont peu nombreuses ou
     *  peu informatives (ex. MD) fait parfois échouer les deux premières tentatives (citant par hasard 2 noms
     *  techniques, ou une phrase un peu courte) alors qu'une question identique posée juste après réussissait du
     *  premier coup : c'est la variance normale d'un petit modèle local (3B, CPU), pas un biais systématique par
     *  module — une tentative de plus réduit la fréquence du repli sans changer son déclenchement légitime (LLM
     *  indisponible, contexte réellement trop mince). */
    private static final int SYNTHESIS_MAX_ATTEMPTS = 3;

    /**
     * Génère la présentation générale d'un module par un appel LLM DÉDIÉ (voir {@link #SYNTHESIS_SYSTEM_PROMPT}),
     * séparé de celui qui produit les rubriques. Jamais vide : si toutes les tentatives échouent (réponse trop
     * courte, en anglais, ou LLM indisponible), {@link #MODULE_INTRO_FALLBACK} sert de dernier recours honnête.
     */
    private String moduleSynthesisIntro(List<RetrieverService.ScoredDocument> context) {
        List<Document> overviews = context.stream()
                .map(RetrieverService.ScoredDocument::document)
                .filter(d -> Document.TABLE_OVERVIEW_COLUMN_MARKER.equals(d.getColumnName()))
                .limit(SYNTHESIS_MAX_TABLES)
                .toList();
        if (overviews.isEmpty()) return MODULE_INTRO_FALLBACK;
        String contextText = overviews.stream()
                .map(d -> d.getContent().length() > SYNTHESIS_DOC_CHARS
                        ? d.getContent().substring(0, SYNTHESIS_DOC_CHARS) : d.getContent())
                .collect(Collectors.joining("\n---\n"));
        String sourceText = overviews.stream().map(d -> d.getContent()).collect(Collectors.joining(" "));
        Map<String, Object> body = Map.of("model", model, "system", SYNTHESIS_SYSTEM_PROMPT,
                "prompt", "Vues d'ensemble des tables :\n" + contextText, "stream", false, "options", SYNTHESIS_OPTIONS);
        for (int attempt = 1; attempt <= SYNTHESIS_MAX_ATTEMPTS; attempt++) {
            GenerateResponse response = postGenerateWithRetry(body);
            String raw = response == null ? null : cutInventedTurns(response.response());
            if (raw == null || raw.isBlank()) continue;
            String cleaned = stripSummaryParagraph(extractLlmModuleIntro(raw));
            cleaned = dropUnsupportedSentences(dropSentencesWithTechnicalNames(cleaned), sourceText);
            if (cleaned.isBlank() || looksLikeEnglish(cleaned) || cleaned.split("\\s+").length < 12) {
                log.warn("Synthèse de présentation de module rejetée (tentative {}/{}) : {}",
                        attempt, SYNTHESIS_MAX_ATTEMPTS, raw);
                continue;
            }
            return cleaned;
        }
        return MODULE_INTRO_FALLBACK;
    }

    static String moduleAnchorFact(List<RetrieverService.ScoredDocument> context) {
        Document summary = context.stream()
                .filter(c -> Document.MODULE_SUMMARY_COLUMN_MARKER.equals(c.document().getColumnName()))
                .map(RetrieverService.ScoredDocument::document).findFirst().orElse(null);
        if (summary == null) return "";
        String content = summary.getContent();
        String code = fieldFromSummary(content, "Module : ");
        String label = fieldFromSummary(content, "Libellé officiel du module : ");
        StringBuilder sb = new StringBuilder("Selon la documentation T24, le module ").append(code == null ? "" : code);
        if (label != null && !label.isBlank()) sb.append(" (libellé officiel « ").append(label).append(" »)");
        return sb.toString();
    }

    /** Une phrase (en tête de l'introduction) qui n'apporte que le décompte des tables ("regroupe/contient N
     *  tables documentées") : un inventaire, jamais une définition — voir {@link #extractLlmModuleIntro}. */
    private static final Pattern LEADING_TABLE_COUNT_SENTENCE = Pattern.compile(
            "(?iu)^[^.!?\\n]{0,60}\\b(regroupe|contient|comporte|compte|dispose de)\\b[^.!?\\n]{0,20}\\btables?\\b"
                    + "[^.!?\\n]*[.!?]\\s*");

    /**
     * Extrait l'introduction FONCTIONNELLE rédigée par le LLM (texte avant la première rubrique "## ") — sa vraie
     * définition du module, synthétisée à partir des vues d'ensemble de tables du contexte — en retirant : sa
     * propre formule de sourçage si elle double {@link #moduleAnchorFact} déjà ajouté séparément, et toute phrase
     * d'ouverture qui ne fait que compter les tables (voir {@link #LEADING_TABLE_COUNT_SENTENCE} : un inventaire
     * n'est pas une définition, même si le LLM y revient malgré la consigne).
     */
    /** "Le module <n'importe quoi> (CODE)" en tête : le LLM re-nomme parfois le module avec un libellé anglais
     *  deviné (ex. "Le module Electronic Banking (EB)"), y compris juste après {@link #moduleAnchorFact} qui
     *  vient pourtant de donner le VRAI libellé — cette clause redondante et potentiellement fausse est retirée,
     *  jamais la suite de la phrase (le verbe et l'explication qui suivent restent, avec sa majuscule remise). */
    private static final Pattern LEADING_SELF_NAMING_CLAUSE = Pattern.compile(
            "(?iu)^(?:le|l['’])\\s*module\\s+[^()\\n]{2,60}\\([A-Z]{2,6}\\)\\s*");

    /** Même idée que {@link #LEADING_SELF_NAMING_CLAUSE}, mais SANS parenthèses : "Le module Electronic Banking
     *  gère..." (le LLM invente un nom anglais et l'utilise directement comme sujet, sans même la parenthèse du
     *  code). Ne retire que le groupe nominal ("Le module Electronic Banking "), jamais le verbe qui suit. */
    private static final Pattern LEADING_SELF_NAMING_CLAUSE_NO_PARENS = Pattern.compile(
            "(?iu)^(?:le|l['’])\\s*module\\s+(?:[A-ZÀ-Ý][\\wà-ÿ]*\\s+){1,4}"
                    + "(?=(?:g[èe]re|est|sert|fournit|permet|couvre|regroupe|contr[ôo]le)\\b)");

    /** Une ligne-titre autonome sans article ("Module EB", "Module Electronic Banking") que le LLM écrit parfois
     *  avant sa vraie phrase d'introduction, redondante avec {@link #moduleAnchorFact} — voir {@link
     *  #extractLlmModuleIntro}. Retirée avant les autres motifs de re-nommage, pour que la phrase qui suit
     *  redevienne le début du texte et soit à son tour reconnue par eux. */
    private static final Pattern LEADING_BARE_TITLE_LINE = Pattern.compile("(?iu)^module\\s+[^\\n]{1,60}\\n+");

    static String extractLlmModuleIntro(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) return "";
        int firstHeading = cleaned.indexOf("##");
        String intro = (firstHeading >= 0 ? cleaned.substring(0, firstHeading) : cleaned).strip();
        intro = LEADING_BARE_TITLE_LINE.matcher(intro).replaceFirst("").stripLeading();
        intro = GROUNDED_OPENER_PATTERN.matcher(intro).find()
                ? intro.replaceFirst("(?iu)^[^.!?\\n]{0,40}\\b(selon|d['’]après)\\b[^,]{0,60},\\s*", "")
                : intro;
        intro = LEADING_TABLE_COUNT_SENTENCE.matcher(intro).replaceFirst("");
        String beforeSelfNaming = intro.stripLeading();
        intro = LEADING_SELF_NAMING_CLAUSE.matcher(beforeSelfNaming).replaceFirst("");
        intro = LEADING_SELF_NAMING_CLAUSE_NO_PARENS.matcher(intro.stripLeading()).replaceFirst("");
        // Le groupe nominal sujet retiré (ex. "Le module Electronic Banking ") laisse une phrase commençant
        // directement par son verbe ("gère les aspects...") : grammaticalement incomplète en français sans
        // pronom sujet ("Il gère...").
        if (!intro.equals(beforeSelfNaming) && !intro.isBlank()) intro = "Il " + intro.stripLeading();
        // Majuscule de tête si la première phrase restante a perdu son ouverture d'origine.
        if (!intro.isEmpty() && Character.isLowerCase(intro.charAt(0))) {
            intro = Character.toUpperCase(intro.charAt(0)) + intro.substring(1);
        }
        return intro.strip();
    }

    /** Repli honnête (jamais inventé) quand le LLM n'a rédigé aucune introduction fonctionnelle exploitable après
     *  filtrage (voir {@link #extractLlmModuleIntro}) : dit explicitement la limite au lieu de laisser un vide ou
     *  d'improviser une définition. */
    private static final String MODULE_INTRO_FALLBACK =
            "Les tables disponibles dans la documentation indexée ne permettent pas de dégager une description "
                    + "fonctionnelle unique et fiable de ce module ; voici les artefacts documentés qui s'y "
                    + "rattachent.";

    /** Voir {@link #moduleIntroSentence} : lit une ligne "Label : valeur" du document de synthèse du module. */
    private static String fieldFromSummary(String content, String label) {
        int start = content.indexOf(label);
        if (start < 0) return null;
        start += label.length();
        int end = content.indexOf('\n', start);
        return (end < 0 ? content.substring(start) : content.substring(start, end)).trim();
    }

    /** Aucune formule de clôture pour une réponse MODULE_GENERAL : la réponse s'arrête juste après la dernière
     *  puce utile, sans phrase artificielle ("N'hésitez pas à me demander...") qui n'apporte aucune information —
     *  demande explicite : mieux vaut une réponse qui s'arrête net qu'une politesse systématique. Conservée sous
     *  forme de constante (vide) pour que le point d'ajout dans {@code generate()} reste visible et documenté. */
    private static final String MODULE_CLOSING_LINE = "";

    /** Isole les rubriques "## ..." d'une réponse MODULE_GENERAL, en retirant toute phrase d'introduction ou de
     *  conclusion que le LLM aurait rédigée malgré la consigne de ne pas le faire (l'intro et la conclusion sont
     *  désormais construites déterministiquement, voir {@link #moduleIntroSentence} et {@link
     *  #MODULE_CLOSING_LINE}) : seul le contenu réellement structuré (titres et puces) est conservé.
     */
    static String isolateModuleRubrics(String answer) {
        if (answer == null || answer.isBlank()) return "";
        int firstHeading = answer.indexOf("##");
        String body = firstHeading >= 0 ? answer.substring(firstHeading) : answer;
        String[] paragraphs = body.split("\n\n+");
        int lastStructured = -1;
        for (int i = 0; i < paragraphs.length; i++) {
            String p = paragraphs[i].strip();
            if (p.startsWith("#") || p.lines().anyMatch(l -> l.stripLeading().startsWith("-"))) lastStructured = i;
        }
        String result = lastStructured < 0 ? body.strip()
                : String.join("\n\n", java.util.Arrays.asList(paragraphs).subList(0, lastStructured + 1)).strip();
        // Filet de sécurité au niveau LIGNE (pas seulement paragraphe) : une phrase de clôture que le LLM accole
        // à la dernière puce SANS ligne vide (ex. "N'hésitez pas à me demander...") reste sinon dans le bloc
        // gardé ci-dessus, puisque tout le bloc contient par ailleurs de vraies puces.
        String[] lines = result.split("\n");
        int lastKept = lines.length - 1;
        while (lastKept >= 0) {
            String l = lines[lastKept].strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("-")) break;
            lastKept--;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, lastKept + 1)).stripTrailing();
    }

    /** Une ligne "- NOM.TABLE : rôle" (voir {@link #dedupTablesAcrossRubrics}). */
    private static final Pattern DASH_TABLE_LINE = Pattern.compile("^-\\s+([A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+)\\s*:.*$");

    /**
     * Garantit déterministiquement qu'une même table n'apparaît jamais sous plusieurs rubriques à la fois (le
     * MODULE_REMINDER le demande, mais le petit modèle local n'y obéit pas toujours) : ne garde que sa PREMIÈRE
     * occurrence, retire les suivantes ; si une rubrique se retrouve alors sans aucune puce, elle est à son tour
     * retirée (voir {@link #stripEmptyModuleSections}).
     */
    /**
     * Retire toute puce "- NOM : rôle" dont NOM n'est PAS une table du contexte (vue d'ensemble réellement
     * présente) — filet de sécurité déterministe contre l'invention de "tables" : observé en pratique, le petit
     * modèle local peut promouvoir au rang de table un simple nom de fichier mentionné EN PASSANT dans la
     * Description d'une autre table (ex. "STMT.ENTRY" cité dans la description d'EB.SYSTEM.ID comme fichier
     * qu'elle documente, puis réutilisé comme une puce "- STMT.ENTRY : fichier des mouvements de compte..." avec
     * une description inventée) — {@link #citesOnlyContextIdentifiers} ne le détecte pas puisque le nom apparaît
     * bien quelque part dans le texte du contexte, seulement pas comme une vraie table qui y est décrite.
     */
    static String keepOnlyKnownTables(String rubrics, Set<String> knownTables) {
        if (rubrics == null || rubrics.isBlank()) return rubrics;
        StringBuilder out = new StringBuilder();
        for (String line : rubrics.split("\n", -1)) {
            Matcher m = DASH_TABLE_LINE.matcher(line.strip());
            if (m.matches() && !knownTables.contains(m.group(1).toUpperCase())) {
                log.warn("Table inventée retirée d'une réponse MODULE_GENERAL (absente des vues d'ensemble du "
                        + "contexte) : {}", m.group(1));
                continue;
            }
            out.append(line).append('\n');
        }
        return stripEmptyModuleSections(out.toString().stripTrailing());
    }

    /** Même forme acceptée que {@link #SUBJECT_FIELD_PATTERN}/{@link #DASH_LIST_SUBJECT_PATTERN} (nom pointé ou
     *  mot nu en MAJUSCULES) : une ligne de liste "- IDENTIFIANT : rôle", pour {@link #keepOnlyKnownIdentifiers}. */
    private static final Pattern DASH_IDENTIFIER_LINE = Pattern.compile(
            "^-\\s+([A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+|[A-Z][A-Z0-9]{2,}(?: [A-Z][A-Z0-9]{2,})?)\\s*:.*$");

    /**
     * Retire toute puce "- IDENTIFIANT : rôle" d'une réponse GÉNÉRALE (hors moduleMode, qui a son propre filtre
     * {@link #keepOnlyKnownTables}) dont IDENTIFIANT n'est NI une table NI un champ réellement distinct parmi les
     * documents retrouvés — même trou que celui comblé par {@link #keepOnlyKnownTables}, mais pour TOPIC_TABLES/
     * OTHER : observé en pratique (module AC, question sur les relevés de compte), le petit modèle local a cité
     * "MVMT.DETAIL" comme s'il s'agissait d'un champ documenté de AC.ALLOCATION.RULE avec son propre rôle, alors
     * que ce nom n'est JAMAIS un champ de cette table — seulement une VALEUR mentionnée dans la Description d'un
     * AUTRE champ (MVMT.TARGET : "The values allowed when MVMT.DETAIL is set to STMT are..."). {@link
     * #citesOnlyContextIdentifiers} ne détecte pas ce cas puisque le mot figure bel et bien dans le texte source.
     */
    static String keepOnlyKnownIdentifiers(String answer, Set<String> knownIdentifiers) {
        if (answer == null || answer.isBlank()) return answer;
        StringBuilder out = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            Matcher m = DASH_IDENTIFIER_LINE.matcher(line.strip());
            if (m.matches() && !knownIdentifiers.contains(m.group(1).toUpperCase())) {
                log.warn("Identifiant inventé retiré d'une réponse (ni table ni champ distinct parmi les "
                        + "documents retrouvés) : {}", m.group(1));
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    static String dedupTablesAcrossRubrics(String rubrics) {
        if (rubrics == null || rubrics.isBlank()) return rubrics;
        Set<String> seen = new HashSet<>();
        StringBuilder out = new StringBuilder();
        for (String line : rubrics.split("\n", -1)) {
            Matcher m = DASH_TABLE_LINE.matcher(line.strip());
            if (m.matches() && !seen.add(m.group(1).toUpperCase())) continue;
            out.append(line).append('\n');
        }
        return stripEmptyModuleSections(out.toString().stripTrailing());
    }

    private static final Pattern UPPER_DOTTED_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+\\b");

    /**
     * Corrige les identifiants légèrement déformés (segment abrégé — "AC.PRE.CLOSURE.DETS" pour le vrai
     * "AC.PRE.CLOSURE.DETAILS" — ou préfixe de module dupliqué) AVANT la vérification stricte de fidélité au
     * contexte ({@link #citesOnlyContextIdentifiers}, qui n'accepte que des identifiants EXACTS) : sans cette
     * correction préalable, une simple abréviation d'UN champ épuisait les 2 tentatives et faisait basculer
     * toute la réponse (par ailleurs fidèle et bien structurée) sur le message de repli générique, alors qu'une
     * simple correction orthographique suffisait.
     */
    static String autoCorrectNearMissIdentifiers(String answer, List<RetrieverService.ScoredDocument> context) {
        if (answer == null || answer.isBlank()) return answer;
        Set<String> known = new HashSet<>();
        for (RetrieverService.ScoredDocument r : context) {
            Document d = r.document();
            known.add(d.getTableName().toUpperCase());
            if (d.getColumnName() != null) known.add(d.getColumnName().toUpperCase());
        }
        String repaired = answer;
        Set<String> seen = new HashSet<>();
        Matcher m = UPPER_DOTTED_TOKEN.matcher(answer);
        while (m.find()) {
            String token = m.group();
            if (known.contains(token) || !seen.add(token)) continue;
            String deduped = token.replaceFirst("^([A-Z0-9]+)\\.\\1\\.", "$1.");
            String fix = !deduped.equals(token) && known.contains(deduped) ? deduped
                    : known.stream().filter(k -> k.contains(".") && FuzzyMatch.isCloseEnough(token, k))
                            .findFirst().orElse(null);
            if (fix != null) repaired = repaired.replace(token, fix);
        }
        return repaired;
    }

    /** Les 4 seuls intitulés de rubrique autorisés pour une réponse MODULE_GENERAL (voir {@link
     *  #MODULE_REMINDER}) — vocabulaire fermé et connu à l'avance, ce qui permet de reconnaître un titre quel
     *  que soit son habillage Markdown (voir {@link #normalizeModuleHeadings}). */
    private static final List<String> MODULE_RUBRIC_TITLES = List.of(
            "Tables et Données", "Processus et Flux de travail", "Configuration et Paramétrage",
            "Utilisateurs et Documentation");

    /**
     * Reconnaît un intitulé de rubrique MODULE_GENERAL quel que soit son habillage — "## Titre", "**Titre**" ou
     * même du texte brut sur sa propre ligne — et le normalise en "## Titre" : le petit modèle local dérive
     * parfois vers "**Titre**" ou du texte brut au lieu de "## " (observé en pratique, surtout après lui avoir
     * demandé d'écrire aussi une introduction substantielle), ce qui faisait échouer {@link
     * #isolateModuleRubrics}/{@link #extractLlmModuleIntro} (aucun "##" trouvé, tout le texte traité comme une
     * seule intro non structurée, dupliquant la réponse entière). Le vocabulaire des titres étant fermé et connu
     * à l'avance (voir {@link #MODULE_RUBRIC_TITLES}), cette reconnaissance est fiable sans dépendre du balisage.
     */
    static String normalizeModuleHeadings(String text) {
        if (text == null || text.isBlank()) return text;
        String result = text;
        for (String title : MODULE_RUBRIC_TITLES) {
            result = result.replaceAll("(?m)^\\s*#{0,4}\\s*\\*{0,2}\\s*" + Pattern.quote(title) + "\\s*\\*{0,2}\\s*:?\\s*$",
                    "## " + title);
        }
        return result;
    }

    /** Un titre "## X" collé à la fin de la phrase précédente, sur la même ligne ou juste après un seul saut de
     *  ligne (observé en pratique chez le petit modèle local, malgré la consigne) : rendu illisible par le
     *  renderer Markdown minimal du frontend, qui a besoin d'une ligne vide pour reconnaître un titre. Capture
     *  volontairement peu (hashes + espace + 1er caractère) : le reste du titre suit tel quel dans le texte
     *  original, seul l'espacement qui le précède est normalisé. */
    private static final Pattern HEADING_TOKEN = Pattern.compile("[ \\t]*\\n?[ \\t]*(#{2,4}\\s+\\S)");

    static String ensureBlankLineBeforeHeadings(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        return HEADING_TOKEN.matcher(answer).replaceAll("\n\n$1").replaceAll("\\n{3,}", "\n\n").stripLeading();
    }

    /** Déterminants/pronoms qui s'élident (perdent leur voyelle finale, remplacée par une apostrophe) devant un mot
     *  commençant par une voyelle ou un h muet — voir {@link #fixFrenchElisions}. */
    private static final Pattern ELISION_PATTERN = Pattern.compile(
            "(?iu)\\b(de|le|la|que|se|ne|je|me|te)\\s+(?=[aeiouyàâäéèêëïîôöùûAEIOUYÀÂÄÉÈÊËÏÎÔÖÙÛ]|h[aeiouyAEIOUY])");

    /**
     * Corrige les fautes d'élision manquante ("de effectuer" au lieu de "d'effectuer", "le application" au lieu
     * de "l'application"...), fréquentes chez le petit modèle local malgré la consigne de français correct — une
     * correction purement orthographique, jamais un risque d'invention (le sens de la phrase est inchangé).
     */
    /** Contractions obligatoires "de/à + le/les" (distinctes de l'élision devant voyelle ci-dessus) : "de le" ->
     *  "du", "de les" -> "des", "à le" -> "au", "à les" -> "aux" — faute fréquente du petit modèle local (ex.
     *  "la description de le champ" au lieu de "la description du champ"). */
    // "à" (préposition accentuée) seulement, jamais "a" nu (verbe avoir, ex. "il a le droit" ne doit jamais
    // devenir "il au droit").
    private static final Pattern CONTRACTION_PATTERN = Pattern.compile("(?iu)\\b(de|à)\\s+(le|les)\\b");

    static String fixFrenchElisions(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        answer = CONTRACTION_PATTERN.matcher(answer).replaceAll(mr -> {
            String prep = mr.group(1).toLowerCase();
            String art = mr.group(2).toLowerCase();
            String out = prep.equals("de") ? (art.equals("le") ? "du" : "des") : (art.equals("le") ? "au" : "aux");
            return Character.isUpperCase(mr.group(1).charAt(0)) ? Character.toUpperCase(out.charAt(0)) + out.substring(1) : out;
        });
        Matcher m = ELISION_PATTERN.matcher(answer);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(answer, last, m.start());
            String word = m.group(1);
            String elided = switch (word.toLowerCase()) {
                case "de" -> "d'";
                case "le", "la" -> "l'";
                case "que" -> "qu'";
                case "se" -> "s'";
                case "ne" -> "n'";
                case "je" -> "j'";
                case "me" -> "m'";
                case "te" -> "t'";
                default -> word + " ";
            };
            // Préserve la majuscule initiale (ex. "Le application" -> "L'application" en tête de phrase) sans
            // mettre TOUT le mot en majuscules (ex. "Que" -> "Qu'", jamais "QU'").
            out.append(Character.isUpperCase(word.charAt(0))
                    ? Character.toUpperCase(elided.charAt(0)) + elided.substring(1)
                    : elided);
            last = m.end();
        }
        out.append(answer, last, answer.length());
        return out.toString();
    }

    /**
     * Filet de sécurité pour la règle 12/16 (chaque tiret sur sa propre ligne) : le petit modèle local aligne
     * parfois plusieurs éléments "- NOM : description" à la suite dans le même paragraphe, sans saut de ligne
     * entre eux (observé en pratique), malgré la consigne. Insère un saut de ligne avant chaque tiret qui
     * introduit un élément de liste (identifié par le motif "- NOM : ", seul cas visé — jamais un simple trait \
     * d'union au milieu d'un mot ou d'une phrase ordinaire, qui n'est jamais suivi de ":"). Sans effet si les \
     * tirets sont déjà chacun sur leur ligne.
     */
    private static final Pattern INLINE_DASH_LIST_ITEM_PATTERN = Pattern.compile(
            "[ \\t]+-\\s+(?=[A-Za-z][A-Za-z0-9]*(?:[. ][A-Za-z0-9]+)*\\s*:)");

    private static String formatDashListOnNewLines(String answer) {
        if (answer == null) return null;
        return INLINE_DASH_LIST_ITEM_PATTERN.matcher(answer).replaceAll("\n- ");
    }

    /**
     * Filet de sécurité pour la règle 17 (jamais de brouillon suivi d'une étiquette méta) : si le LLM échoue
     * malgré tout à la suivre et fait précéder sa réponse finale d'un brouillon rejeté, marqué par une étiquette
     * comme "RÉPONSE_ATTENDUE :"/"Réponse idéale :"/"Voici ma réponse :", ne garde que ce qui suit cette
     * étiquette (la version que le modèle désigne lui-même comme la bonne), plutôt que d'afficher les deux à la
     * suite. Sans effet si aucune étiquette de ce type n'apparaît.
     */
    private static final Pattern DRAFT_LABEL_PATTERN = Pattern.compile(
            "(?iu)(?:r[ée]ponse[_ ]id[ée]ale|r[ée]ponse[_ ]attendue|voici (?:ma|la) r[ée]ponse)\\s*:\\s*");

    private static String stripDraftLabel(String answer) {
        if (answer == null) return null;
        Matcher m = DRAFT_LABEL_PATTERN.matcher(answer);
        if (!m.find()) return answer;
        String after = answer.substring(m.end()).trim();
        if (after.length() >= 2 && (after.charAt(0) == '"' || after.charAt(0) == '«')
                && (after.charAt(after.length() - 1) == '"' || after.charAt(after.length() - 1) == '»')) {
            after = after.substring(1, after.length() - 1).trim();
        }
        return after.isEmpty() ? answer.substring(0, m.start()).trim() : after;
    }

    /**
     * Vérifie que tout identifiant désigné comme SUJET ("le champ X", "la table X") dans {@code answer}
     * correspond réellement à l'une des colonnes/tables effectivement retrouvées — jamais un autre nom, même
     * s'il ressemble à un vrai identifiant T24 et même s'il apparaît par ailleurs dans une description (ex:
     * "DOCUMENT.CODE" mentionné dans la description de "DOCUMENTS.REQ" sans en être le sujet) : observé en
     * pratique, un simple contrôle "apparaît quelque part dans le texte du contexte" ne suffit pas à l'écarter.
     * <p>Si {@code module} est fourni, une citation préfixée par ce code module (ex. "AC.ACCOUNT" pour la table
     * "ACCOUNT" retrouvée en module AC) est aussi acceptée sans ce préfixe : observé en pratique, le LLM reprend
     * naturellement le style "MODULE.NOM" de la question même quand le contexte ne connaît la table/colonne que
     * sous son nom seul (ex. le contexte dit "Table : ACCOUNT", et le LLM répond "le champ AC.ACCOUNT sert...") —
     * sans cette tolérance, une réponse pourtant fidèle au contexte était rejetée à tort, aucune des deux tentatives
     * ne validant jamais, et l'appelant basculait sur le texte brut du contexte au lieu d'une synthèse naturelle.
     * Inversement, une citation qui OMET le préfixe déjà présent dans le vrai nom de table (ex. le contexte
     * connaît "LD.LOANS.AND.DEPOSITS", le LLM répond juste "LOANS.AND.DEPOSITS") est acceptée de la même façon —
     * sans cette seconde tolérance, une citation pourtant fidèle (juste dépouillée du préfixe module par le LLM)
     * était rejetée à tort exactement comme une vraie hallucination, pour la même raison que ci-dessus.
     */
    private static boolean citesOnlyContextIdentifiers(String answer, List<RetrieverService.ScoredDocument> context,
                                                         String module, String conversationHistory) {
        // Un nom déjà cité dans la conversation (question ou réponse précédente) est légitime même s'il n'est pas
        // dans le contexte de CE tour (ex. "et dans quelle table il se trouve ?" -> "Le champ CURRENCY se trouve
        // dans la table ACCOUNT", CURRENCY venant de la question précédente).
        String historyUpper = conversationHistory == null ? "" : conversationHistory.toUpperCase();
        Set<String> knownIdentifiers = new HashSet<>();
        for (RetrieverService.ScoredDocument c : context) {
            knownIdentifiers.add(c.document().getColumnName().toUpperCase());
            knownIdentifiers.add(c.document().getTableName().toUpperCase());
        }
        String modulePrefix = module != null && !module.isBlank() ? module.trim().toUpperCase() + "." : null;

        // Règle générale : tout nom POINTÉ en majuscules cité n'importe où dans la réponse doit apparaître dans le
        // contexte réellement récupéré (noms de tables/colonnes ou texte des descriptions, où une mention
        // secondaire est légitime). Observé en pratique : réponse "le champ ... est COPIES (AC.STMT.HANDOFF)"
        // alors qu'aucune source ne contenait ce champ — jamais détecté par les motifs "le champ X" plus haut.
        Matcher historyTokens = Pattern.compile("[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)*").matcher(historyUpper);
        while (historyTokens.find()) knownIdentifiers.add(historyTokens.group());
        StringBuilder blob = new StringBuilder(historyUpper).append(' ');
        for (RetrieverService.ScoredDocument c : context) {
            blob.append(c.document().getTableName()).append(' ').append(c.document().getContent()).append(' ');
        }
        String contextUpper = blob.toString().toUpperCase();
        // Contrôle limité au début de la réponse (là où se trouve le sujet répondu) : plus loin, une mention d'un
        // autre nom repris de la description est légitime.
        Matcher dotted = UPPER_DOTTED_PATTERN.matcher(answer.length() > 250 ? answer.substring(0, 250) : answer);
        while (dotted.find()) {
            String token = dotted.group();
            if (contextUpper.contains(token)) continue;
            if (modulePrefix != null && token.startsWith(modulePrefix)
                    && contextUpper.contains(token.substring(modulePrefix.length()))) continue;
            return false;
        }

        String head = answer.length() > 250 ? answer.substring(0, 250) : answer;
        return allCitationsKnown(SUBJECT_FIELD_PATTERN.matcher(head), knownIdentifiers, modulePrefix, contextUpper)
                && allCitationsKnown(DASH_LIST_SUBJECT_PATTERN.matcher(answer), knownIdentifiers, modulePrefix, contextUpper);
    }

    /** Nom pointé en MAJUSCULES (au moins deux segments), ex. AC.STMT.HANDOFF — jamais "etc." ni "i.e.". */
    private static final Pattern UPPER_DOTTED_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:\\.[A-Z0-9]+)+\\b");

    private static boolean allCitationsKnown(Matcher m, Set<String> knownIdentifiers, String modulePrefix,
                                              String contextUpper) {
        while (m.find()) {
            String cited = m.group(m.groupCount()).toUpperCase();
            // Un nom pointé simplement mentionné dans le texte du contexte (ex. "table PD.PARAMETER" citée par la
            // description d'un champ) est une mention légitime, pas une invention.
            if (cited.contains(".") && contextUpper.contains(cited)) continue;
            // "la table LANGUAGE" (mot nu) n'est presque toujours qu'une mention d'une AUTRE table rencontrée dans
            // une description, pas le sujet de la réponse : seuls les noms pointés sont contrôlés après "table".
            if (m.groupCount() == 2 && "table".equalsIgnoreCase(m.group(1)) && !cited.contains(".")) continue;
            if (knownIdentifiers.contains(cited)) continue;
            if (modulePrefix != null && cited.startsWith(modulePrefix)
                    && knownIdentifiers.contains(cited.substring(modulePrefix.length()))) continue;
            if (modulePrefix != null && knownIdentifiers.contains(modulePrefix + cited)) continue;
            return false;
        }
        return true;
    }

    /**
     * Un raté isolé de l'appel LLM (ex. Ollama momentanément occupé par la traduction de fond au démarrage) fait
     * basculer l'appelant sur un texte brut non naturel (voir ChatController) — une seule tentative supplémentaire
     * après une brève pause absorbe ces ratés transitoires sans presque jamais doubler le temps de réponse en
     * pratique (best-effort : {@code null} si les deux tentatives échouent, comme avant).
     */
    private GenerateResponse postGenerateWithRetry(Map<String, Object> body) {
        try {
            return doPostGenerate(body);
        } catch (Exception first) {
            log.warn("Échec de l'appel au LLM local (Ollama), nouvelle tentative : {}", first.getMessage());
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                return doPostGenerate(body);
            } catch (Exception second) {
                log.warn("Échec de l'appel au LLM local (Ollama) après nouvelle tentative : {}", second.getMessage());
                return null;
            }
        }
    }

    private GenerateResponse doPostGenerate(Map<String, Object> body) {
        return restClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(GenerateResponse.class);
    }

    /** Limite la longueur des réponses générées : sur ce matériel (CPU, sans GPU), le temps de génération est
     *  proportionnel au nombre de tokens produits — plafonner évite des réponses interminables et lentes. Relevé
     *  de 350 à 500 : une réponse énumérant plusieurs champs avec une courte description de chacun (règle 12 du
     *  system prompt) ne tenait pas dans 350 tokens, ce qui produisait des réponses tronquées ou réduites à une
     *  simple liste de noms sans explication. */
    private static final int MAX_RESPONSE_TOKENS = 700;

    /** Température basse pour les réponses de chat : un petit modèle (3B) livré à une température par défaut
     *  (~0.8) donne une qualité et une fiabilité d'instruction très inégales d'une exécution à l'autre pour une
     *  même question (parfois une bonne réponse précise, parfois une réponse qui hésite ou ignore le contexte
     *  fourni). Une température basse mais non nulle réduit fortement cette variabilité tout en gardant une
     *  formulation naturelle (contrairement à 0.0, réservé aux tâches strictement déterministes comme la
     *  traduction ou le choix d'un module dans une liste fermée). */
    private static final double ANSWER_TEMPERATURE = 0.2;

    /** Fenêtre de contexte explicite : le prompt système (règles + exemples) dépasse à lui seul 3 000 tokens et
     *  le contexte peut y ajouter la description complète d'une table (~10 000 caractères) ; avec la fenêtre par
     *  défaut d'Ollama, le DÉBUT du prompt (donc les règles) est silencieusement tronqué, ce qui dégrade la
     *  fidélité et le respect des consignes. */
    private static final int CONTEXT_WINDOW_TOKENS = 8192;

    /** Séquences d'arrêt : le modèle s'arrête s'il commence à écrire lui-même un nouveau tour de conversation
     *  ("Question : ...") à la place de l'utilisateur (voir aussi cutInventedTurns). */
    private static final List<String> STOP_SEQUENCES = List.of(
            "\nQuestion :", "\nQuestion:", "\nUtilisateur :", "\nUtilisateur:", "\nAssistant :", "\nAssistant:");

    /** Pénalise légèrement la répétition : le petit modèle local boucle sur une même phrase quand la Description
     *  source est longue (observé : « Ce code de charge est défini sur la base d'un montant de ... » en boucle). */
    private static final double REPEAT_PENALTY = 1.15;

    private static final Map<String, Object> ANSWER_OPTIONS = Map.of("num_predict", MAX_RESPONSE_TOKENS,
            "temperature", ANSWER_TEMPERATURE, "num_ctx", CONTEXT_WINDOW_TOKENS, "stop", STOP_SEQUENCES,
            "repeat_penalty", REPEAT_PENALTY);

    private static final ObjectMapper STREAM_JSON = new ObjectMapper();

    @Override
    public void generateStreaming(String question, List<RetrieverService.ScoredDocument> context, String conversationHistory,
                                   Consumer<String> onToken) {
        String contextText = context.stream()
                .map(c -> MandatoryClassifier.withFreshMandatoryLine(c.document().getContent()))
                .collect(Collectors.joining("\n---\n"));
        String prompt = (conversationHistory != null && !conversationHistory.isBlank()
                ? "Historique de la conversation :\n" + conversationHistory + "\n\n"
                : "")
                + "Contexte :\n" + contextText + "\n\nQuestion : " + question;
        streamOllama(withBrevityOverride(SYSTEM_PROMPT, question), prompt, onToken);
    }

    @Override
    public void generateWithoutContextStreaming(String question, String conversationHistory, Consumer<String> onToken) {
        String prompt = (conversationHistory != null && !conversationHistory.isBlank()
                ? "Historique de la conversation :\n" + conversationHistory + "\n\n"
                : "") + "Question : " + question;
        streamOllama(withBrevityOverride(FALLBACK_SYSTEM_PROMPT, question), prompt, onToken);
    }

    /** Appelle Ollama en mode {@code stream: true} et invoque {@code onToken} pour chaque fragment reçu (une ligne
     *  JSON par fragment, format NDJSON natif d'Ollama). Best-effort : log et arrêt silencieux en cas d'échec. */
    private void streamOllama(String systemPrompt, String prompt, Consumer<String> onToken) {
        try {
            restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "system", systemPrompt, "prompt", prompt,
                            "stream", true, "options", ANSWER_OPTIONS))
                    .exchange((req, res) -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                JsonNode node = STREAM_JSON.readTree(line);
                                String piece = node.path("response").asText("");
                                if (!piece.isEmpty()) onToken.accept(piece);
                                if (node.path("done").asBoolean(false)) break;
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Échec du streaming LLM local (Ollama) : {}", e.getMessage());
        }
    }

    @Override
    public String generateWithoutContext(String question, String conversationHistory) {
        String prompt = (conversationHistory != null && !conversationHistory.isBlank()
                ? "Historique de la conversation :\n" + conversationHistory + "\n\n"
                : "") + "Question : " + question;
        Map<String, Object> body = Map.of("model", model, "system",
                withBrevityOverride(FALLBACK_SYSTEM_PROMPT, question), "prompt", prompt, "stream", false, "options", ANSWER_OPTIONS);

        // Même garde qu'en generate() (voir looksLikeEnglish) : ce repli connaissances générales est soumis à la
        // même règle 7 (réponse intégralement en français), et au même risque de refus en anglais du petit
        // modèle local malgré la consigne.
        String lastAnswer = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            GenerateResponse response = postGenerateWithRetry(body);
            String answer = response == null ? null : response.response();
            if (answer == null) return lastAnswer;
            lastAnswer = stripGenericFillerSentences(
                    fixFrenchElisions(formatDashListOnNewLines(stripDraftLabel(stripBulletAsterisks(answer)))));
            if (!looksLikeEnglish(answer)) return lastAnswer;
            log.warn("Réponse LLM (sans contexte) en anglais malgré la consigne (tentative {}/2) : {}", attempt, answer);
        }
        return lastAnswer;
    }

    private static final String QUERY_REWRITE_SYSTEM_PROMPT = """
            Tu reformules la question d'un employé de banque pour une recherche documentaire dans le HelpText \
            Temenos T24. Ta seule tâche : si la question actuelle fait référence implicitement à quelque chose \
            de précis mentionné dans l'historique (via "il"/"elle"/"ce champ"/"cette table"/"ça"/"le \
            deuxième"/"la première"/une question elliptique sans sujet...), réécris-la en une question autonome \
            qui reprend explicitement ce nom de champ/table/module exact — sans jamais changer le sens, sans \
            ajouter d'information absente de l'historique, sans répondre à la question toi-même. Si la question \
            est déjà autonome et précise, renvoie-la exactement telle quelle.

            Cas particulier 1 : "le premier"/"le deuxième"/"le troisième"... désigne le Nième élément d'une liste \
            de champs ou de tables donnée par l'Assistant dans l'historique (compte à partir de 1, dans l'ordre \
            où ils apparaissent) — identifie ce nom précis dans cette liste et reprends-le explicitement.

            Cas particulier 2 : "chaque champ"/"chaque table"/"tous les champs"/"toutes ces tables"/"chacun"/ \
            "chacune" désigne TOUS les éléments d'une liste donnée par l'Assistant dans l'historique — reprends \
            alors explicitement TOUS leurs noms exacts (pas seulement le premier), séparés par des virgules.

            Règle absolue : ne modifie JAMAIS l'orthographe d'un nom de champ, de table ou de module déjà présent \
            dans la question ou l'historique (ex: ACCOUNT.CLOSURE, MIN.COMM.AMT.LCCY) — copie-le caractère pour \
            caractère, même s'il te semble mal écrit, même s'il ressemble à un mot du libellé du module rappelé \
            dans l'historique. Ne "corrige" et ne "normalise" jamais un tel identifiant : ce n'est pas ton rôle \
            ici, seulement de reformuler la phrase autour.

            Réponds UNIQUEMENT avec la question (reformulée ou non), sans commentaire ni guillemets autour.

            Exemple 1 :
            Historique :
            Utilisateur : MIN.COMM.AMT.LCCY sert à quoi ?
            Assistant : Selon la documentation T24, MIN.COMM.AMT.LCCY permet de définir...
            Question actuelle : "il appartient à quelle table ?"
            RÉPONSE_ATTENDUE : "MIN.COMM.AMT.LCCY appartient à quelle table ?"

            Exemple 2 — référence ordinale à une liste :
            Historique :
            Utilisateur : quels champs permettent d'identifier un compte ?
            Assistant : Selon la documentation T24, voici les principaux champs : - ACCOUNT.NUMBER : identifie le \
            compte. - CUSTOMER : identifie le client propriétaire. - CATEGORY : identifie la catégorie du compte.
            Question actuelle : "peux-tu m'expliquer le deuxième ?"
            RÉPONSE_ATTENDUE : "Peux-tu m'expliquer le champ CUSTOMER ?"

            Exemple 3 — référence à TOUS les éléments d'une liste :
            Historique :
            Utilisateur : donne moi les champs qui identifient un compte
            Assistant : Selon la documentation T24, les champs sont : - CUSTOMER - ACCOUNT.NUMBER - \
            ACCOUNT.TITLE.1 - CURRENCY
            Question actuelle : "tu peux me detailler chaque champ"
            RÉPONSE_ATTENDUE : "Peux-tu détailler les champs CUSTOMER, ACCOUNT.NUMBER, ACCOUNT.TITLE.1 et \
            CURRENCY ?\"""";

    /** Un identifiant T24 explicite dans la question d'origine (nom pointé comme ACCOUNT.CLOSURE, ou mot en
     *  MAJUSCULES comme OVERRIDE) : voir {@link #isSafeRewrite}. */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+\\b|\\b[A-Z][A-Z0-9]{2,}\\b");

    /**
     * Marqueur indiquant que la question fait référence à quelque chose d'implicite (pronom, question elliptique
     * sans sujet propre) : seule condition sous laquelle {@link #isSafeRewrite} autorise la reformulation à
     * introduire un identifiant absent de la question elle-même, en le copiant depuis l'historique. Sans un tel
     * marqueur, la question est déjà autonome et complète (ex: "Quelle table gère les versions d'écran de la Home
     * Page Teller ?") : lui injecter un identifiant du sujet précédent serait une fausse continuité, pas une
     * résolution de référence légitime (observé en pratique : "OVERRIDE" injecté à tort dans une question sans
     * rapport, simplement parce qu'il figurait dans l'historique).
     */
    private static final Pattern REFERENTIAL_MARKER_PATTERN = Pattern.compile(
            "(?iu)\\b(il|elle|ils|elles|celui-ci|celle-ci|ce champ|ces champs|cette table|ces tables|"
                    + "cette colonne|ces colonnes|ça|cela|ce dernier|cette dernière|"
                    + "le premier|la première|le second|la seconde|le deuxi[èe]me|la deuxi[èe]me|"
                    + "le troisi[èe]me|la troisi[èe]me|le quatri[èe]me|la quatri[èe]me|le cinqui[èe]me|"
                    + "la cinqui[èe]me|le \\d+(?:er|[èe]me|e)|"
                    + "chaque champ|chaque table|chacun|chacune|tous les champs|toutes les tables|"
                    + "tous ces champs|toutes ces tables)\\b");

    @Override
    public String rewriteQuery(String question, String conversationHistory) {
        if (conversationHistory == null || conversationHistory.isBlank()) return question;
        if (question == null || question.isBlank()) return question;
        String prompt = "Historique :\n" + conversationHistory + "\n\nQuestion actuelle : \"" + question + "\"";
        try {
            GenerateResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "system", QUERY_REWRITE_SYSTEM_PROMPT, "prompt", prompt,
                            "stream", false, "options", Map.of("temperature", 0.0)))
                    .retrieve()
                    .body(GenerateResponse.class);
            String rewritten = response == null ? null : response.response();
            if (rewritten == null) return question;
            rewritten = rewritten.trim();
            // Un petit modèle local peut entourer sa réponse de guillemets malgré la consigne, ou répondre vide :
            // dans les deux cas on nettoie plutôt que de risquer une recherche sur un texte mal formé.
            if (rewritten.length() >= 2 && rewritten.startsWith("\"") && rewritten.endsWith("\"")) {
                rewritten = rewritten.substring(1, rewritten.length() - 1).trim();
            }
            if (rewritten.isBlank() || !isSafeRewrite(question, conversationHistory, rewritten)) return question;
            return rewritten;
        } catch (Exception e) {
            log.warn("Échec de la reformulation de requête (LLM indisponible), question originale conservée : {}", e.getMessage());
            return question;
        }
    }

    /**
     * Deux garanties avant d'accepter une reformulation, chacune suffisante pour la rejeter entièrement (retour à
     * la question d'origine) :
     * 1. Aucun identifiant déjà présent dans la question d'origine n'a été altéré (ex: "ACCOUNT.CLOSURE" réécrit
     *    en "Accounts.CLOSURE" à cause du libellé du module "Accounts" rappelé dans l'historique — observé en
     *    pratique).
     * 2. Aucun NOUVEL identifiant n'a été introduit sans provenir de {@code conversationHistory} : le seul moyen
     *    légitime pour la reformulation d'ajouter un identifiant absent de la question (ex. résoudre "il" en
     *    "MIN.COMM.AMT.LCCY") est de le copier depuis l'historique fourni ; un identifiant qui n'y figure pas non
     *    plus n'a pu être qu'inventé — un petit modèle local peut halluciner un identifiant réel mais sans rapport
     *    (observé en pratique : "CAP.INTEREST" alors que le sujet réel était "AV.NAU.CR.MVMT"), qui déclencherait
     *    ensuite une correspondance exacte trompeuse avec un score de confiance maximal si on lui faisait confiance.
     */
    private static boolean isSafeRewrite(String original, String conversationHistory, String rewritten) {
        String rewrittenUpper = rewritten.toUpperCase();
        Matcher originalIds = IDENTIFIER_PATTERN.matcher(original);
        while (originalIds.find()) {
            if (!rewrittenUpper.contains(originalIds.group().toUpperCase())) return false;
        }

        String originalUpper = original.toUpperCase();
        String historyUpper = conversationHistory == null ? "" : conversationHistory.toUpperCase();
        boolean hasReferentialMarker = REFERENTIAL_MARKER_PATTERN.matcher(original).find();
        Matcher newIds = IDENTIFIER_PATTERN.matcher(rewritten);
        while (newIds.find()) {
            String token = newIds.group().toUpperCase();
            if (originalUpper.contains(token)) continue;
            // Un identifiant absent de la question elle-même ne peut être accepté que s'il y a un vrai besoin de
            // résolution de référence (marqueur présent) ET que l'identifiant provient bien de l'historique.
            if (!hasReferentialMarker || !historyUpper.contains(token)) return false;
        }
        return true;
    }

    private static final String TRANSLATE_SYSTEM_PROMPT = """
            Tu traduis des descriptions techniques de champs Temenos T24 de l'anglais vers le français, pour des \
            employés de banque. Traduis fidèlement et simplement. Garde tels quels (sans les traduire) les noms \
            de champs, tables ou modules en majuscules avec points (ex: AMOUNT, CUSTOMER.ID, LD.LOANS) ainsi que \
            les acronymes techniques. Réponds UNIQUEMENT avec la traduction, sans commentaire ni guillemets \
            autour du texte.""";

    @Override
    public String translateToFrench(String text) {
        if (text == null || text.isBlank()) return text;
        try {
            GenerateResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "system", TRANSLATE_SYSTEM_PROMPT, "prompt", text, "stream", false,
                            "options", Map.of("temperature", 0.0)))
                    .retrieve()
                    .body(GenerateResponse.class);
            String translated = response == null ? null : response.response();
            return translated != null && !translated.isBlank() ? translated.trim() : text;
        } catch (Exception e) {
            log.warn("Échec de la traduction (LLM indisponible), texte original conservé : {}", e.getMessage());
            return text;
        }
    }

    private static final String BATCH_TRANSLATE_SYSTEM_PROMPT = """
            Tu traduis une liste de descriptions techniques de tables Temenos T24 de l'anglais vers le français, \
            pour des employés de banque. Traduis fidèlement et simplement. Garde tels quels (sans les traduire) \
            les noms de champs, tables ou modules en majuscules avec points (ex: AMOUNT, CUSTOMER.ID, LD.LOANS) \
            ainsi que les acronymes techniques. Chaque ligne du texte fourni est numérotée "N. texte" : réponds \
            avec EXACTEMENT le même nombre de lignes, dans le même ordre, au même format "N. traduction" (une \
            traduction par ligne, sans saut de ligne à l'intérieur d'une traduction, sans commentaire ni ligne \
            supplémentaire).""";

    private static final Pattern NUMBERED_LINE_PATTERN = Pattern.compile("^\\s*(\\d+)\\.\\s*(.*)$");

    @Override
    public List<String> translateBatchToFrench(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (texts.size() == 1) return List.of(translateToFrench(texts.get(0)));

        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            prompt.append(i + 1).append(". ").append(texts.get(i).replace("\n", " ").replace("\r", " ")).append("\n");
        }

        try {
            GenerateResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "system", BATCH_TRANSLATE_SYSTEM_PROMPT, "prompt", prompt.toString(),
                            "stream", false, "options", Map.of("temperature", 0.0)))
                    .retrieve()
                    .body(GenerateResponse.class);
            List<String> parsed = parseNumberedTranslations(response == null ? null : response.response(), texts.size());
            if (parsed != null) return parsed;
            log.warn("Réponse de traduction groupée mal formée ({} textes attendus), repli en individuel.", texts.size());
        } catch (Exception e) {
            log.warn("Échec de la traduction groupée (LLM), repli en individuel : {}", e.getMessage());
        }
        // Repli : traduction individuelle, plus lente pour ce lot mais fiable (jamais de mélange/perte).
        return texts.stream().map(this::translateToFrench).toList();
    }

    /** Retourne null (repli) si le nombre de lignes numérotées ne correspond pas exactement à {@code expectedCount}. */
    private static List<String> parseNumberedTranslations(String raw, int expectedCount) {
        if (raw == null || raw.isBlank()) return null;
        Map<Integer, String> byNumber = new TreeMap<>();
        for (String line : raw.split("\n")) {
            Matcher m = NUMBERED_LINE_PATTERN.matcher(line.trim());
            if (m.matches()) {
                String text = m.group(2).trim();
                if (!text.isEmpty()) byNumber.put(Integer.parseInt(m.group(1)), text);
            }
        }
        if (byNumber.size() != expectedCount) return null;
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= expectedCount; i++) {
            String text = byNumber.get(i);
            if (text == null) return null;
            result.add(text);
        }
        return result;
    }

    private static final String TABLE_SUMMARY_SYSTEM_PROMPT = """
            Tu es un expert Temenos T24. À partir du nom d'une table et de la description de ses colonnes, \
            rédige en français UNE SEULE phrase concise résumant à quoi sert cette table dans son ensemble. Ne \
            liste pas les colonnes une par une, synthétise leur usage global. Réponds uniquement avec cette \
            phrase, sans préambule ni guillemets.""";

    @Override
    public String summarizeTable(String tableName, List<String> columnDescriptions) {
        if (columnDescriptions.isEmpty()) return null;
        String prompt = "Table : " + tableName + "\nColonnes :\n" + String.join("\n", columnDescriptions);
        try {
            GenerateResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "system", TABLE_SUMMARY_SYSTEM_PROMPT, "prompt", prompt, "stream", false))
                    .retrieve()
                    .body(GenerateResponse.class);
            String summary = response == null ? null : response.response();
            return summary != null && !summary.isBlank() ? summary.trim() : null;
        } catch (Exception e) {
            log.warn("Échec de la synthèse de description de table : {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String resolveModule(String query, List<ModuleOption> options) {
        if (options.isEmpty() || query == null || query.isBlank()) return null;
        String q = query.trim();

        // 1) L'utilisateur a peut-être tapé directement le code du module seul (y compris un module technique/local
        // sans libellé humain connu, ex. "ACCCSM") : correspondance exacte, aucune ambiguïté possible.
        for (ModuleOption o : options) {
            if (o.code().equalsIgnoreCase(q)) return o.code();
        }

        // ... ou nommé le code au sein d'une phrase (ex. "c'est quoi le module AZ ?") : on cherche un token
        // juste à côté du mot "module" plutôt que n'importe quel mot de la phrase. Le candidat doit être en
        // MAJUSCULES dans le texte d'origine (pas de CASE_INSENSITIVE sur le groupe capturé) : sinon un simple
        // article ou mot français ("le module...", "la module...") matcherait dès qu'il coïncide avec un vrai
        // code de module existant (ex. "LE" est un vrai module de ce déploiement, comme "SA", "CA", "DE").
        Matcher moduleWord = Pattern.compile("(?i:module)\\s+([A-Z]{2,10})\\b|\\b([A-Z]{2,10})\\s+(?i:module)")
                .matcher(q);
        while (moduleWord.find()) {
            String candidate = moduleWord.group(1) != null ? moduleWord.group(1) : moduleWord.group(2);
            for (ModuleOption o : options) {
                if (o.code().equalsIgnoreCase(candidate)) return o.code();
            }
        }

        // Ce déploiement T24 compte plusieurs centaines de modules (dont une majorité de codes techniques/locaux
        // sans libellé humain, ex. "UKBBSI", "ACCCSM") : inutile et contre-productif de les soumettre au LLM,
        // qui ne pourrait de toute façon pas deviner leur sens en langage naturel. On ne raisonne qu'sur les
        // modules ayant un vrai libellé (extrait de portal-help-menu.xml).
        List<ModuleOption> labeled = options.stream().filter(o -> o.label() != null && !o.label().isBlank()).toList();
        if (labeled.isEmpty()) return null;

        // 2) Correspondance déterministe sur le libellé (rapide, fiable, ne dépend pas du LLM) : le texte demandé
        // est essentiellement le nom du module (ex. "TELLER" pour le libellé "Teller"), sans ambiguïté.
        List<ModuleOption> containsMatches = labeled.stream()
                .filter(o -> o.label().equalsIgnoreCase(q)
                        || o.label().toLowerCase().contains(q.toLowerCase())
                        || q.toLowerCase().contains(o.label().toLowerCase()))
                .toList();
        if (containsMatches.size() == 1) return containsMatches.get(0).code();

        // 3) Ambiguïté déterministe (plusieurs libellés contiennent le texte demandé) : liste déjà courte, on la
        // soumet telle quelle au LLM.
        if (containsMatches.size() > 1) return askLlmToPickModule(q, containsMatches);

        // 3bis) Mots-clés métier courants (français/anglais) pour les modules les plus demandés : un petit modèle
        // local, quand il est incertain, a tendance à retomber systématiquement sur le même module par défaut
        // (observé : "AC" pour des questions sans rapport comme "ordres permanents" ou "banque électronique") au
        // lieu de dire honnêtement qu'il ne sait pas. Cette liste, plus fiable, est essayée avant de risquer ça.
        String qLower = normalize(q);
        for (Map.Entry<String, List<String>> entry : MODULE_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (containsKeyword(qLower, normalize(keyword))) {
                    for (ModuleOption o : options) {
                        if (o.code().equalsIgnoreCase(entry.getKey())) return o.code();
                    }
                }
            }
        }

        // 4) Cas multilingue/conceptuel (ex. "crédits" -> "Lending", aucun recouvrement de mots) : soumettre les
        // ~405 modules labellisés d'un coup à un petit modèle local est peu fiable (il se perd dans la liste et
        // choisit au hasard, ex. "CAGPGR" pour "crédits"). On tente d'abord la petite liste des modules les plus
        // courants (déjà connus/nommés correctement, ex. LD="Lending"), et seulement si ça échoue, la liste
        // complète en dernier recours.
        List<ModuleOption> priority = labeled.stream()
                .filter(o -> PRIORITY_MODULE_CODES.contains(o.code().toUpperCase()))
                .toList();
        if (!priority.isEmpty()) {
            String fromPriority = askLlmToPickModule(q, priority);
            if (fromPriority != null) return fromPriority;
        }
        return askLlmToPickModule(q, labeled);
    }

    /** Modules les plus couramment questionnés (voir aussi COMMON_MODULE_CODES/PRIORITY_MODULES ailleurs dans l'appli). */
    private static final List<String> PRIORITY_MODULE_CODES = List.of(
            "AA", "AC", "AZ", "EB", "FT", "FX", "IC", "LC", "LD", "MM", "SC", "ST", "TT");

    /**
     * Mots-clés métier (français/anglais, sans accents) pour les modules les plus demandés en langage naturel :
     * repli déterministe avant le LLM, qui a tendance à retomber sur le même module par défaut quand il est
     * incertain plutôt que de le dire honnêtement.
     */
    private static final Map<String, List<String>> MODULE_KEYWORDS = Map.ofEntries(
            Map.entry("AC", List.of("compte", "comptes", "account", "accounts")),
            Map.entry("EB", List.of("banque electronique", "electronic banking", "internet banking", "banque en ligne", "e-banking")),
            Map.entry("FT", List.of("virement", "virements", "transfert de fonds", "funds transfer", "wire transfer")),
            Map.entry("FX", List.of("change", "devises", "devise", "foreign exchange", "taux de change")),
            Map.entry("IC", List.of("interets", "frais", "charges", "interest and charges")),
            Map.entry("LC", List.of("lettre de credit", "lettres de credit", "letter of credit", "credit documentaire")),
            Map.entry("LD", List.of("credit", "credits", "pret", "prets", "lending", "loan", "loans", "emprunt")),
            Map.entry("MM", List.of("marche monetaire", "money market")),
            Map.entry("SC", List.of("titres", "valeurs mobilieres", "securities", "actions", "obligations")),
            Map.entry("ST", List.of("ordre permanent", "ordres permanents", "standing order", "standing orders")),
            Map.entry("TT", List.of("guichet", "especes", "caisse", "teller", "cash", "retrait", "depot especes")));

    /** Minuscules et sans accents, pour comparer indépendamment des accents saisis ou non par l'utilisateur. */
    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /** Vrai si {@code normalizedKeyword} apparaît dans {@code normalizedText} comme un mot/expression entier,
     *  jamais comme simple sous-chaîne — sans quoi le mot-clé "change" (module FX) matchait à tort le verbe
     *  "changeons" ("changeons de sujet"), créant une fausse ambiguïté qui bloquait toute détection de module. */
    private static boolean containsKeyword(String normalizedText, String normalizedKeyword) {
        return Pattern.compile("\\b" + Pattern.quote(normalizedKeyword) + "\\b").matcher(normalizedText).find();
    }

    private static final String DOMAIN_SYSTEM_PROMPT = "Tu classes les questions d'employés d'une banque. Réponds "
            + "UNIQUEMENT par OUI si la question concerne la banque, la finance, Temenos T24 ou leurs données et "
            + "documentation (modules, tables, champs, comptes, crédits, virements, devises...). Réponds "
            + "UNIQUEMENT par NON pour tout le reste : informatique générale, cuisine, culture, et toute donnée "
            + "personnelle ou privée (salaire, identité, dossier RH...).";

    @Override
    public boolean isInDomain(String question) {
        try {
            GenerateResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "system", DOMAIN_SYSTEM_PROMPT, "prompt", question, "stream", false,
                            "options", Map.of("temperature", 0.0, "num_predict", 4)))
                    .retrieve()
                    .body(GenerateResponse.class);
            String raw = response == null || response.response() == null ? "" : response.response().trim();
            return !raw.toUpperCase().startsWith("NON");
        } catch (Exception e) {
            log.warn("Classification de domaine indisponible (LLM), question traitée normalement : {}", e.getMessage());
            return true;
        }
    }

    @Override
    public String suggestOtherModule(String question, String currentModule) {
        if (question == null || question.isBlank()) return null;
        String qLower = normalize(question);
        Set<String> matches = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : MODULE_KEYWORDS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(currentModule)) continue;
            for (String keyword : entry.getValue()) {
                if (containsKeyword(qLower, normalize(keyword))) {
                    matches.add(entry.getKey());
                    break;
                }
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private String askLlmToPickModule(String query, List<ModuleOption> candidates) {
        String moduleList = candidates.stream()
                .map(o -> o.code() + " : " + o.label())
                .collect(Collectors.joining("\n"));
        String prompt = """
                Voici une liste de modules T24 (code : libellé) :
                %s

                Un employé décrit, en langage naturel et informel (français ou anglais), ce qu'il veut consulter : "%s"

                Réponds UNIQUEMENT avec le code exact du module le plus pertinent parmi la liste ci-dessus, sans \
                aucun autre mot ni ponctuation. Si aucun module ne correspond raisonnablement, réponds exactement : AUCUN""".formatted(moduleList, query);

        try {
            GenerateResponse response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "prompt", prompt, "stream", false, "options", Map.of("temperature", 0.0)))
                    .retrieve()
                    .body(GenerateResponse.class);
            String raw = response == null || response.response() == null ? "" : response.response().trim();
            if (raw.isEmpty()) return null;

            // Le LLM a normalement répondu avec le seul code : on vérifie d'abord une correspondance exacte,
            // puis, s'il a ajouté du texte autour malgré la consigne, on cherche un code de la liste dans la réponse.
            for (ModuleOption o : candidates) {
                if (o.code().equalsIgnoreCase(raw)) return o.code();
            }
            for (ModuleOption o : candidates) {
                if (Pattern.compile("\\b" + Pattern.quote(o.code()) + "\\b", Pattern.CASE_INSENSITIVE).matcher(raw).find()) {
                    return o.code();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Échec de l'appel au LLM pour la résolution de module : {}", e.getMessage());
            return null;
        }
    }

    private record GenerateResponse(String response,
                                     @com.fasterxml.jackson.annotation.JsonProperty("done_reason") String doneReason) {}
}
