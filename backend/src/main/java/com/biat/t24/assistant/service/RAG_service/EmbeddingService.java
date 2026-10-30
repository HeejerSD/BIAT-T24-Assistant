package com.biat.t24.assistant.service.RAG_service;

import java.util.List;

public interface EmbeddingService {

    /**
     * Calcule un vecteur d'embedding pour chaque texte, dans l'ordre, en un minimum d'appels réseau
     * (par lots). Un élément est {@code null} si l'embedding de ce texte précis a échoué.
     */
    List<float[]> embedBatch(List<String> texts);
}
