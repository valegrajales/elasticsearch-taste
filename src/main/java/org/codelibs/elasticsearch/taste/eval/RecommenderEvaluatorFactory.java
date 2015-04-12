package org.codelibs.elasticsearch.taste.eval;

import java.util.Map;

public interface RecommenderEvaluatorFactory {
    void init(Map<String, Object> settings);

    RMSRecommenderEvaluator create();
}
