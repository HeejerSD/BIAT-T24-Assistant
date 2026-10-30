package com.biat.t24.assistant.service.RAG_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calcule les embeddings via le modèle local Hugging Face exposé par le microservice FAISS
 * (endpoint POST /embed, sentence-transformers, gratuit et sans limite).
 * Les textes sont envoyés par lots pour limiter le nombre de requêtes réseau.
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);
    private static final int BATCH_SIZE = 100;

    private final RestClient restClient;

    public EmbeddingServiceImpl(@Value("${biat.faiss.base-url:http://localhost:8001}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        if (texts.isEmpty()) return results;

        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()));
            results.addAll(embedOneBatch(batch));
        }
        return results;
    }

    private List<float[]> embedOneBatch(List<String> batch) {
        try {
            EmbedResponse response = restClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("texts", batch))
                    .retrieve()
                    .body(EmbedResponse.class);

            if (response == null || response.vectors() == null) {
                log.warn("Réponse vide du service d'embedding local pour un lot de {} chunk(s)", batch.size());
                return nulls(batch.size());
            }
            return response.vectors().stream()
                    .map(EmbeddingServiceImpl::toFloatArray)
                    .toList();
        } catch (Exception e) {
            log.warn("Échec de l'appel d'embedding local pour un lot de {} chunk(s) : {}", batch.size(), e.getMessage());
            return nulls(batch.size());
        }
    }

    private static List<float[]> nulls(int size) {
        List<float[]> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(null);
        return list;
    }

    private static float[] toFloatArray(List<Double> values) {
        if (values == null) return null;
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) arr[i] = values.get(i).floatValue();
        return arr;
    }

    private record EmbedResponse(List<List<Double>> vectors) {}
}
