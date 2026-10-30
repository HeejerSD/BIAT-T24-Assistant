package com.biat.t24.assistant.config;

import java.util.List;
import java.util.Set;

/**
 * Modules T24 sur lesquels l'assistant est volontairement restreint (AC, LC, TT, LD, EB, DE, MD). Les autres
 * modules déjà importés restent en base (au cas où), mais sont exclus de l'index vectoriel global et de la
 * résolution de module côté chat, pour garder une recherche rapide et éviter que le chat ne réponde sur des
 * modules hors périmètre.
 */
public final class ActiveModules {

    public static final List<String> CODES = List.of("AC", "LC", "TT", "LD", "EB", "DE", "MD");
    public static final Set<String> CODE_SET = Set.copyOf(CODES);

    private ActiveModules() {}
}
