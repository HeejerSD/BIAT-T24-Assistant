package com.biat.t24.assistant.service.RAG_service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Client HTTP vers le microservice Python FAISS (recherche par similarité vectorielle, un index par "scope"). */
@Service
public class FaissClient {

    private final RestClient restClient;

    public FaissClient(@Value("${biat.faiss.base-url:http://localhost:8001}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    public record IdVector(long id, float[] vector) {}
    public record ScoredId(long id, double score) {}

    public void upsert(String scope, List<IdVector> items) {
        if (items.isEmpty()) return;
        List<UpsertItem> body = items.stream().map(i -> new UpsertItem(i.id(), i.vector())).toList();
        restClient.post()
                .uri("/indices/{scope}/upsert", scope)
                .body(new UpsertRequest(body))
                .retrieve()
                .toBodilessEntity();
    }

    public void delete(String scope, List<Long> ids) {
        if (ids.isEmpty()) return;
        restClient.post()
                .uri("/indices/{scope}/delete", scope)
                .body(new DeleteRequest(ids))
                .retrieve()
                .toBodilessEntity();
    }

    public List<ScoredId> search(String scope, float[] vector, int k) {
        SearchResponse response = restClient.post()
                .uri("/indices/{scope}/search", scope)
                .body(new SearchRequest(vector, k))
                .retrieve()
                .body(SearchResponse.class);
        if (response == null || response.hits() == null) return List.of();
        return response.hits().stream().map(h -> new ScoredId(h.id(), h.score())).toList();
    }

    public record RerankCandidate(long id, String text) {}

    /**
     * Réordonne un petit lot de candidats déjà présélectionnés (typiquement par similarité cosinus) selon un
     * score de pertinence (question, texte) calculé jointement par un cross-encoder — plus précis, mais trop
     * coûteux pour tout un index. Retourne les mêmes ids, triés par pertinence décroissante (liste vide si le
     * service échoue ou si {@code candidates} est vide).
     */
    public List<ScoredId> rerank(String query, List<RerankCandidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        RerankResponse response = restClient.post()
                .uri("/rerank")
                .body(new RerankRequest(query, candidates))
                .retrieve()
                .body(RerankResponse.class);
        if (response == null || response.scores() == null) return List.of();
        return response.scores().stream().map(s -> new ScoredId(s.id(), s.score())).toList();
    }

    private record UpsertItem(long id, float[] vector) {}
    private record UpsertRequest(List<UpsertItem> items) {}
    private record DeleteRequest(List<Long> ids) {}
    private record SearchRequest(float[] vector, int k) {}
    private record Hit(long id, double score) {}
    private record SearchResponse(List<Hit> hits) {}
    private record RerankRequest(String query, List<RerankCandidate> candidates) {}
    private record RerankResponse(List<Hit> scores) {}
}
