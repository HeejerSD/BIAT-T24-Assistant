package com.biat.t24.assistant.controller;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerLogicTest {

    @Test
    void enumeratesTableFieldsDetectsAllMandatoryFieldsQuestion() {
        // Bug trouve en pratique (table AC.STMT.REGEN.REQUEST, 9 champs reels) : avec topK=5, une recherche
        // semantique scopee a la table ne retrouvait pas forcement TOUS les champs reellement obligatoires parmi
        // les 5 mieux classes pour la question precise ("quels sont les champs obligatoires ?") - ACCOUNT.NUMBER,
        // pourtant marque "Mandatory field", etait absent du contexte et donc jamais cite. enumeratesTableFields
        // detecte ce cas pour elargir topK (voir son usage dans ask/handleStreamingAsk).
        assertTrue(ChatController.enumeratesTableFields(
                "Quels sont les champs obligatoires de la table AC.STMT.REGEN.REQUEST ?", "AC.STMT.REGEN.REQUEST"));
        assertTrue(ChatController.enumeratesTableFields(
                "Liste tous les champs obligatoires de cette table", "AC.ALLOCATION.RULE"));
        // Question sur UN champ deja nomme (singulier "champ") : ne doit pas declencher l'enumeration complete.
        assertFalse(ChatController.enumeratesTableFields(
                "Le champ ACCOUNT.NUMBER est-il obligatoire ?", "AC.STMT.REGEN.REQUEST"));
        // Aucune table identifiee : ne peut pas s'appliquer.
        assertFalse(ChatController.enumeratesTableFields("Quels sont les champs obligatoires de cette table ?", null));
    }

    @Test
    void explicitModuleCodeOverridesStaleFrontendSelection() {
        // Bug remonté en pratique : une fois le module "MD" sélectionné via le dialogue guidé, taper "tu peux me
        // présenter le module EB ?" renvoyait une réponse construite sur MD (le frontend n'avait pas mis à jour
        // sa sélection), sans aucun indice pour l'utilisateur. Le module cité explicitement dans la question doit
        // l'emporter sur l'ancienne sélection.
        assertEquals(Optional.of("EB"), ChatController.explicitModuleCodeIn("tu peux me presenter le module EB ?"));
        assertEquals(Optional.of("AC"), ChatController.explicitModuleCodeIn("Présente-moi le module AC"));
        // Insensible à la casse du code tapé par l'utilisateur.
        assertEquals(Optional.of("LD"), ChatController.explicitModuleCodeIn("le module ld m'intéresse"));
    }

    @Test
    void explicitModuleCodeIgnoresUnknownOrAbsentCode() {
        // Un mot quelconque après "module" (nom complet, pas le code) n'est pas confondu avec un code connu :
        // laisse la sélection existante du frontend inchangée plutôt que de la remplacer par une fausse détection.
        assertEquals(Optional.empty(), ChatController.explicitModuleCodeIn("le module Lending m'intéresse"));
        assertEquals(Optional.empty(), ChatController.explicitModuleCodeIn("comment configurer ce champ ?"));
    }

    @Test
    void shortNegativeFindingIsMainlyUncertain() {
        assertTrue(ChatController.isMainlyUncertain(
                "Aucun champ ne correspond à cette description dans la documentation indexée."));
        assertTrue(ChatController.isMainlyUncertain(
                "Je ne trouve pas cette information dans le contexte fourni, il n'y a aucun champ de ce nom."));
    }

    @Test
    void lateAsideInARichAnswerIsNotMainlyUncertain() {
        // Bug trouve en verifiant le correctif TOPIC_TABLES : une phrase incidente ("il n'y a pas d'information sur
        // son fonctionnement") au milieu d'une reponse par ailleurs riche et fondee ne doit pas faire disparaitre
        // entierement les cartes source ni le tableau de champs.
        String richAnswer = "Selon la documentation T24, le champ TT.HP.VERSION de la table TT.TELLER.HP.VERSIONS "
                + "sert a specifier les versions a utiliser.\n\n"
                + "Pour ce champ, il n'y a pas d'information documentee sur son fonctionnement.\n\n"
                + "**Proprietes documentees**\n"
                + "- Type : NON SPECIFIE\n"
                + "- Obligatoire : NON SPECIFIE\n"
                + "- Format : non specifie dans le contexte\n"
                + "- Longueur : non specifiee\n"
                + "- Valeur par defaut : non specifiee";
        assertFalse(ChatController.isMainlyUncertain(richAnswer));
    }

    @Test
    void repeatedIdentifierPrefixIsCollapsed() {
        // Bug trouve en re-testant "Que peut-on trouver dans le module TT ?" : le modele double parfois le code
        // du module en tete d'un identifiant qu'il ecrit correctement par ailleurs.
        assertEquals("TT.TELLER.HP.VERSIONS", ChatController.collapseRepeatedPrefix("TT.TT.TELLER.HP.VERSIONS"));
        // Un identifiant normal (premier segment non repete) reste inchange.
        assertEquals("LD.LOANS.AND.DEPOSITS", ChatController.collapseRepeatedPrefix("LD.LOANS.AND.DEPOSITS"));
        // Une repetition legitime plus loin dans l'identifiant (pas en tete) n'est jamais touchee.
        assertEquals("AC.BALANCE.TYPE.AC", ChatController.collapseRepeatedPrefix("AC.BALANCE.TYPE.AC"));
    }

    @Test
    void unrelatedConditionalSentenceIsNotMainlyUncertain() {
        // Bug trouve en verifiant le correctif TOPIC_TABLES : une clause conditionnelle decrivant un comportement
        // documente ("si X n'est pas specifie, le systeme fait Y") n'est pas un aveu de lacune documentaire.
        String businessRuleAnswer = "Selon la documentation T24, la table TT.TELLER.HP.VERSIONS joue un role "
                + "crucial dans le module Teller.\n\n"
                + "Cette table est utilisee pour selectionner les versions appropriees a utiliser pendant ces "
                + "operations. Si une version n'est pas specifiee dans ce fichier de parametres, le systeme Teller "
                + "sera lance automatiquement au lieu de la version specifiee lors de l'entree de la transaction.\n\n"
                + "La table TT.TELLER.HP.VERSIONS contient plusieurs colonnes qui servent a definir les versions a "
                + "utiliser pour des operations specifiques. Ces colonnes sont :\n\n"
                + "- TT.AUTH.VERSION : specifie les versions a utiliser comme drill-downs.\n"
                + "- TT.HP.VERSION : definit les versions a utiliser comme drill-downs.\n"
                + "- TT.GEN.VERSION : specifie les versions a utiliser comme drill-downs.\n"
                + "- DESCRIPTION : contient une description generale.";
        assertFalse(ChatController.isMainlyUncertain(businessRuleAnswer));
    }
}
