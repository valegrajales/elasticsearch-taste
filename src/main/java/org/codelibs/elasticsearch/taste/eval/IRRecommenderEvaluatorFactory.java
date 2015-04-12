package org.codelibs.elasticsearch.taste.eval;

import java.util.Map;

public interface IRRecommenderEvaluatorFactory {
    void init(Map<String, Object> settings);

    RecommenderIRStatsEvaluator create();
}
