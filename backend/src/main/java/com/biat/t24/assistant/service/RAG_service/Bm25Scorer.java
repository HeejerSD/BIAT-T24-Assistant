package com.biat.t24.assistant.service.RAG_service;

import com.biat.t24.assistant.model.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 (Okapi), calculé à la demande sur un petit corpus (les chunks d'une table ou d'un module) : complète la
 * recherche dense (embeddings, {@link RetrieverServiceImpl}) par une correspondance par mots exacts, utile pour
 * des termes rares/techniques (codes, acronymes) qu'un embedding généraliste représente mal. Recalculé à chaque
 * appel plutôt que maintenu en index persistant : les corpus concernés (une table, ou un module) restent assez
 * petits pour que ce calcul reste rapide (quelques millisecondes), sans la complexité d'un index à tenir à jour.
 */
final class Bm25Scorer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+(?:\\.[a-z0-9]+)*");
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) return tokens;
        Matcher m = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (m.find()) {
            // Un token de moins de 3 caractères n'est jamais un identifiant T24 significatif, mais coïncide
            // souvent avec un mot grammatical français ("on", "la", "du"...) qui matche alors par hasard un mot
            // anglais ordinaire de la documentation (ex: "trouve-t-on" -> "on", qui matche "based on",
            // "calculated on"...), polluant le score BM25 avec des documents sans rapport réel avec la question.
            if (m.group().length() >= 3) tokens.add(m.group());
        }
        return tokens;
    }

    /** Retourne les ids des {@code topN} documents de {@code corpus} les plus pertinents selon BM25 pour {@code query}. */
    static List<Long> topN(String query, List<Document> corpus, int topN) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || corpus.isEmpty()) return List.of();

        Map<Long, Map<String, Long>> termFreqByDoc = new HashMap<>();
        Map<Long, Integer> lengthByDoc = new HashMap<>();
        Map<String, Integer> docFreq = new HashMap<>();
        double totalLength = 0;

        for (Document doc : corpus) {
            List<String> tokens = tokenize(doc.getContent());
            Map<String, Long> termFreq = tokens.stream().collect(Collectors.groupingBy(t -> t, Collectors.counting()));
            termFreqByDoc.put(doc.getId(), termFreq);
            lengthByDoc.put(doc.getId(), tokens.size());
            totalLength += tokens.size();
            for (String term : termFreq.keySet()) docFreq.merge(term, 1, Integer::sum);
        }
        double avgLength = totalLength / corpus.size();
        int n = corpus.size();

        List<Map.Entry<Long, Double>> scored = new ArrayList<>();
        for (Document doc : corpus) {
            Map<String, Long> termFreq = termFreqByDoc.get(doc.getId());
            int length = lengthByDoc.get(doc.getId());
            double score = 0.0;
            Set<String> countedQueryTerms = new HashSet<>();
            for (String qTerm : queryTokens) {
                if (!countedQueryTerms.add(qTerm)) continue;
                long tf = termFreq.getOrDefault(qTerm, 0L);
                if (tf == 0) continue;
                int df = docFreq.getOrDefault(qTerm, 0);
                double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1);
                double denom = tf + K1 * (1 - B + B * length / avgLength);
                score += idf * (tf * (K1 + 1)) / denom;
            }
            if (score > 0) scored.add(Map.entry(doc.getId(), score));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.stream().limit(topN).map(Map.Entry::getKey).toList();
    }

    private Bm25Scorer() {}
}
